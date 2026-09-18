package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.function.Function;
import org.icij.datashare.Entity;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.DocReference;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.SearchQuery;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.extract.queue.DocumentQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import static java.lang.Integer.parseInt;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_DURATION;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_SIZE;
import static org.icij.datashare.cli.DatashareCliOptions.NEXT_STAGE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_PIPELINE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_DURATION_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_SIZE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SEARCH_QUERY_OPT;

@TemporalSingleActivityWorkflow(name = "enqueue-ner-tasks-from-index", activityOptions = @ActivityOpts(timeout = "P7D"))
@TaskGroup(TaskGroupType.Java)
public class EnqueueFromIndexTask extends PipelineTask<String> {
    private final DocumentCollectionFactory<String> factory;
    private final String searchQuery;
    Logger logger = LoggerFactory.getLogger(getClass());
    private final Pipeline.Type nlpPipeline;
    private final String projectName;
    private final Indexer indexer;
    private final String scrollDuration;
    private final int scrollSize;
    /** Stages whose task drains the queue named after them: a stage before ENQUEUEIDX consumes file
     *  paths rather than document ids, and CREATENLPBATCHESFROMIDX and BATCHNLP read the index, so
     *  enqueuing for any of them strands the documents instead of failing. */
    private static final Set<Stage> QUEUE_CONSUMING_STAGES = EnumSet.of(Stage.CATEGORIZE, Stage.NLP, Stage.ARTIFACT);

    @Inject
    public EnqueueFromIndexTask(final DocumentCollectionFactory<String> factory, final Indexer indexer,
                                @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> ignored) {
        super(Stage.ENQUEUEIDX, taskView.getUser(), factory, new PropertiesProvider(taskView.args), String.class);
        this.factory = factory;
        this.indexer = indexer;
        this.nlpPipeline = Pipeline.Type.parse(
                (String) taskView.args.getOrDefault(NLP_PIPELINE_OPT, Pipeline.Type.CORENLP.name()));
        this.projectName = (String) taskView.args.getOrDefault(DEFAULT_PROJECT_OPT, DEFAULT_DEFAULT_PROJECT);
        this.scrollDuration = propertiesProvider.get(SCROLL_DURATION_OPT).orElse(DEFAULT_SCROLL_DURATION);
        this.scrollSize = parseInt(propertiesProvider.get(SCROLL_SIZE_OPT).orElse(String.valueOf(DEFAULT_SCROLL_SIZE)));
        this.searchQuery = propertiesProvider.get(SEARCH_QUERY_OPT).orElse(null);
    }

    @Override
    public Long call() throws Exception {
        super.call();
        PipelineHelper pipeline = new PipelineHelper(propertiesProvider);
        Stage nextStage = nextStage(pipeline);
        Indexer.Searcher searcher;
        if (searchQuery == null) {
            Indexer.QueryBuilderSearcher builder = indexer.search(singletonList(projectName), Document.class);
            if (nextStage == Stage.NLP) {
                builder = builder.without(nlpPipeline);
            }
            searcher = builder.withSource("rootDocument").limit(scrollSize);
        } else {
            searcher = indexer.search(singletonList(projectName), Document.class, new SearchQuery(searchQuery))
                              .withoutSource("content", "contentTranslated").limit(scrollSize);
        }
        searcher.sort("language", Indexer.Searcher.SortOrder.ASC);
        List<? extends Entity> docsToProcess = searcher.scroll(scrollDuration).collect(toList());
        long totalHits = searcher.totalHits();
        String pipelineInfo = (nextStage == Stage.NLP) ? " excluding already processed by " + nlpPipeline : "";
        logger.info("enqueuing doc ids for index {} targeting {}{} with {} scroll and size of {} : {} documents found",
                    projectName, nextStage, pipelineInfo, scrollDuration, scrollSize, totalHits);

        String outputQueueName = pipeline.getQueueNameFor(nextStage);
        try (DocumentQueue<String> outputQueue = factory.createQueue(outputQueueName, String.class)) {
            do {
                docsToProcess.forEach(doc -> outputQueue.add(DocReference.fromDocument((Document) doc).toQueueEntry()));
                docsToProcess = searcher.scroll(scrollDuration).toList();
            } while (!docsToProcess.isEmpty());
            searcher.clearScroll();
            logger.info("enqueued into {} {} files", outputQueue.getName(), totalHits);
        }
        return totalHits;
    }

    /** The output queue is resolved from --nextStage in {@link #call()}, so the stages-chain default
     *  the base class would build here is never read. */
    @Override
    protected String getOutputQueueName() {
        return null;
    }

    /** Resolved on the run path, not in the constructor: an invalid --nextStage thrown from a
     *  reflectively constructed task becomes a requeue-forever NackException instead of a clean
     *  task error. */
    private Stage nextStage(PipelineHelper pipeline) {
        return propertiesProvider.get(NEXT_STAGE_OPT).map(EnqueueFromIndexTask::parseNextStage)
                                 .orElseGet(() -> pipeline.getNextStage(Stage.ENQUEUEIDX));
    }

    private static Stage parseNextStage(String value) {
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
