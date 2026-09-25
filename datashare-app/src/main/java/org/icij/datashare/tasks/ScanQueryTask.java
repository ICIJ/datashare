package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
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
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import static java.lang.Integer.parseInt;
import static java.lang.String.valueOf;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.PropertiesProvider.REPORT_NAME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_DURATION;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_SIZE;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_DURATION_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_SIZE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SEARCH_QUERY_OPT;

/**
 * Enqueues the file paths a search query selects, so the next stage extracts them again with
 * whatever extraction options the run carries. The mirror image of {@link ScanIndexTask}, which
 * fills the report map so INDEX skips what it already extracted.
 */
@TemporalSingleActivityWorkflow(name = "scan-query", activityOptions = @ActivityOpts(timeout = "P7D"))
@TaskGroup(TaskGroupType.Java)
public class ScanQueryTask extends PipelineTask<Path> {
    private static final Set<Stage> PATH_CONSUMING_STAGES = Stage.consuming(Stage.Payload.PATH);
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final String projectName;
    private final String scrollDuration;
    private final String scrollSize;
    private final String searchQuery;

    @Inject
    public ScanQueryTask(DocumentCollectionFactory<Path> factory, final Indexer indexer, @Assisted Task<Long> taskView,
                         @Assisted Function<Double, Void> ignored) {
        super(Stage.SCANQUERY, taskView.getUser(), factory, new PropertiesProvider(taskView.args), Path.class);
        this.searchQuery = propertiesProvider.get(SEARCH_QUERY_OPT).filter(query -> !query.isBlank()).orElse(null);
        this.scrollDuration = propertiesProvider.get(SCROLL_DURATION_OPT).orElse(DEFAULT_SCROLL_DURATION);
        this.scrollSize = propertiesProvider.get(SCROLL_SIZE_OPT).orElse(valueOf(DEFAULT_SCROLL_SIZE));
        this.projectName = propertiesProvider.get(DEFAULT_PROJECT_OPT).orElse(DEFAULT_DEFAULT_PROJECT);
        this.indexer = indexer;
    }

    @Override
    public Long call() throws Exception {
        super.call();
        checkRunIsUsable();
        logger.info("selecting paths to re-extract in index {} with query \"{}\", {} scroll, scroll size {}",
                    projectName, searchQuery, scrollDuration, scrollSize);
        long nbEnqueued = enqueueSelectedPaths();
        logger.info("enqueued {} paths into {}", nbEnqueued, outputQueue.getName());
        return nbEnqueued;
    }

    private long enqueueSelectedPaths() throws Exception {
        Indexer.Searcher search =
                indexer.search(singletonList(projectName), Document.class, new SearchQuery(searchQuery))
                       .withSource("path").limit(parseInt(scrollSize));
        long nbEnqueued = 0;
        List<? extends Entity> selected;
        try {
            do {
                selected = search.scroll(scrollDuration).collect(toList());
                nbEnqueued += enqueue(selected);
            } while (!selected.isEmpty());
        } finally {
            // released here too when a page or an enqueue throws, so a cancelled or failed run does
            // not leave an open scroll context pinning segments on the cluster
            search.clearScroll();
        }
        return nbEnqueued;
    }

    private long enqueue(List<? extends Entity> docs) throws InterruptedException {
        long nbEnqueued = 0;
        for (Entity entity : docs) {
            Path path = ((Document) entity).getPath();
            // a json query carries no type filter, so it can match an entity that has no path
            if (path != null) {
                outputQueue.put(path);
                nbEnqueued++;
            }
        }
        return nbEnqueued;
    }

    /**
     * Validated here rather than in the constructor: a task built reflectively turns a constructor
     * throw into a requeue-forever NackException instead of a clean task failure.
     */
    private void checkRunIsUsable() {
        if (searchQuery == null) {
            throw new IllegalArgumentException(
                    "%s selects the files to re-extract, so it needs --searchQuery".formatted(Stage.SCANQUERY));
        }
        PipelineHelper pipeline = new PipelineHelper(propertiesProvider);
        Stage nextStage = pipeline.getNextStage(Stage.SCANQUERY);
        if (!PATH_CONSUMING_STAGES.contains(nextStage)) {
            throw new IllegalArgumentException(
                    "%s enqueues file paths, which %s does not drain: expected one of %s next in --stages".formatted(
                            Stage.SCANQUERY, nextStage, PATH_CONSUMING_STAGES));
        }
        propertiesProvider.get(REPORT_NAME_OPT).ifPresent(reportName -> logger.warn(
                "--reportName {} is set: the INDEX stage skips paths already recorded as extracted, so this " +
                "re-extraction may extract nothing. Drop it to force it.", reportName));
        if (!pipeline.stages.contains(Stage.DEDUPLICATE)) {
            logger.warn("{} is not in --stages: a container matched by several embedded documents will be extracted " +
                        "once per match. Add it to collapse the duplicates.", Stage.DEDUPLICATE);
        }
    }
}
