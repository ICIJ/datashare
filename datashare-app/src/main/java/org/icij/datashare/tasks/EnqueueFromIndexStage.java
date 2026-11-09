package org.icij.datashare.tasks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.icij.datashare.Entity;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageActivityOptions;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageProcessor;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.text.DocReference;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.SearchQuery;
import org.icij.datashare.text.nlp.Pipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static java.lang.Integer.parseInt;
import static java.util.Collections.singletonList;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_DURATION;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_SIZE;
import static org.icij.datashare.cli.DatashareCliOptions.NEXT_STAGE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_PIPELINE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_DURATION_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_SIZE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SEARCH_QUERY_OPT;

/**
 * Stage that adds documents in the queue based on a searchQuery
 */
public class EnqueueFromIndexStage implements StageProcessor<Void, String> {
    private static final Set<Stage> QUEUE_CONSUMING_STAGES = Stage.consuming(Stage.Payload.ID);
    private static final StageActivityOptions ACTIVITY_OPTIONS = new StageActivityOptions(Duration.ofDays(7), 0);
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;

    public EnqueueFromIndexStage(Indexer indexer) {
        this.indexer = indexer;
    }

    @Override
    public Stage stage() {
        return Stage.ENQUEUEIDX;
    }

    @Override
    public Class<Void> itemType() {
        return Void.class;
    }

    /** @throws IllegalArgumentException when --nextStage is not a stage that drains the ids this stage emits */
    @Override
    public StageResult process(List<Void> batch, StageContext<String> ctx) {

        PropertiesProvider properties = new PropertiesProvider(ctx.options());
        Stage nextStage = nextStage(properties);
        String projectName = ctx.project().getName();
        Pipeline.Type nlpPipeline = Pipeline.Type.parse(properties.get(NLP_PIPELINE_OPT).orElse(Pipeline.Type.CORENLP.name()));
        String scrollDuration = properties.get(SCROLL_DURATION_OPT).orElse(DEFAULT_SCROLL_DURATION);
        int scrollSize = parseInt(properties.get(SCROLL_SIZE_OPT).orElse(String.valueOf(DEFAULT_SCROLL_SIZE)));
        String searchQuery = properties.get(SEARCH_QUERY_OPT).orElse(null);

        Indexer.Searcher searcher;
        long totalHits;
        if (searchQuery == null) {
            Indexer.QueryBuilderSearcher builder = indexer.search(singletonList(projectName), Document.class);
            //TODO : this is weird code, that might be resolved via an option selected by user, rather than by tracking the next stage.
            //TODO : plus, what if now, with the introduction of other stages, NLP comes after another stage ?
            if (nextStage == Stage.NLP) {
                builder = builder.without(nlpPipeline);
            }
            searcher = builder;
        } else {
            searcher = indexer.search(singletonList(projectName), Document.class, new SearchQuery(searchQuery));
        }
        searcher.withDocValues("rootDocument").limit(scrollSize).sort("language", Indexer.Searcher.SortOrder.ASC);
        long enqueued = 0;
        try {
            List<? extends Entity> docsToProcess = searcher.scroll(scrollDuration).toList();
            totalHits = searcher.totalHits();
            String pipelineInfo = (nextStage == Stage.NLP) ? " excluding already processed by " + nlpPipeline : "";
            logger.info("enqueuing doc ids for index {} targeting {}{} with {} scroll and size of {} : {} documents found",
                        projectName, nextStage, pipelineInfo, scrollDuration, scrollSize, totalHits);
            while (!docsToProcess.isEmpty() && !ctx.isCancelled()) {
                ctx.emit(docsToProcess.stream().map(doc -> DocReference.fromDocument((Document) doc).toQueueEntry())
                                      .toList());
                enqueued += docsToProcess.size();
                docsToProcess = searcher.scroll(scrollDuration).toList();
            }
            searcher.clearScroll();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        logger.info("enqueued {} files for {}", enqueued, nextStage);
        return new StageResult(enqueued, 0, Math.max(0, totalHits - enqueued), 0);
    }

    @Override
    public StageActivityOptions activityOptions() {
        return ACTIVITY_OPTIONS;
    }

    /**
     * The stage receiving the ids: --nextStage when set, the stage following this one in the stages chain otherwise.
     * Resolved when the stage runs, not when its task is built: an invalid --nextStage thrown from a reflectively
     * constructed task becomes a requeue-forever NackException instead of a clean task error.
     */
    private Stage nextStage(PropertiesProvider properties) {
        return parseNextStage(properties.get(NEXT_STAGE_OPT)
                                        .orElseGet(() -> new PipelineHelper(properties).getNextStage(Stage.ENQUEUEIDX)
                                                                                       .name()));
    }

    public static Stage parseNextStage(String value) {
        Stage stage = Stage.parse(value).orElseThrow(
                () -> new IllegalArgumentException("unknown --nextStage value \"%s\"".formatted(value)));
        if (!QUEUE_CONSUMING_STAGES.contains(stage)) {
            throw new IllegalArgumentException(
                    "--nextStage %s has no task draining its queue, expected one of %s".formatted(stage,
                                                                                                  QUEUE_CONSUMING_STAGES));
        }
        return stage;
    }
}
