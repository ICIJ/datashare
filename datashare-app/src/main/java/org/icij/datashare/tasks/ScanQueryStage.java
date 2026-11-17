package org.icij.datashare.tasks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.icij.datashare.Entity;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageActivityOptions;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageProcessor;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static java.lang.Integer.parseInt;
import static java.lang.String.valueOf;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.PropertiesProvider.REPORT_NAME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_DURATION;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_SIZE;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_DURATION_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_SIZE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SEARCH_QUERY_OPT;

public class ScanQueryStage implements StageProcessor<Void, Path> {
    private static final Set<Stage> PATH_CONSUMING_STAGES = Stage.consuming(Stage.Payload.PATH);
    private static final StageActivityOptions ACTIVITY_OPTIONS = new StageActivityOptions(Duration.ofDays(7), 0);
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;

    public ScanQueryStage(Indexer indexer) {
        this.indexer = indexer;
    }

    @Override
    public Stage stage() {
        return Stage.SCANQUERY;
    }

    @Override
    public Class<Void> itemType() {
        return Void.class;
    }

    @Override
    public StageActivityOptions activityOptions() {
        return ACTIVITY_OPTIONS;
    }

    /** @throws IllegalArgumentException when the run has no query, or the stage after this one drains no paths */
    @Override
    public StageResult process(List<Void> batch, StageContext<Path> ctx) {
        PropertiesProvider properties = new PropertiesProvider(ctx.options());
        String searchQuery = properties.get(SEARCH_QUERY_OPT).filter(query -> !query.isBlank()).orElse(null);
        checkRunIsUsable(properties, searchQuery);
        String projectName = ctx.project().getName();
        String scrollDuration = properties.get(SCROLL_DURATION_OPT).orElse(DEFAULT_SCROLL_DURATION);
        int scrollSize = parseInt(properties.get(SCROLL_SIZE_OPT).orElse(valueOf(DEFAULT_SCROLL_SIZE)));
        logger.info("selecting paths to re-extract in index {} with query \"{}\", {} scroll, scroll size {}",
                    projectName, searchQuery, scrollDuration, scrollSize);

        Indexer.Searcher search =
                indexer.search(singletonList(projectName), Document.class, new SearchQuery(searchQuery))
                       .withDocValues("path").limit(scrollSize).sort("_doc", Indexer.Searcher.SortOrder.ASC);
        long nbEmitted = 0;
        try {
            List<? extends Entity> selected;
            do {
                selected = search.scroll(scrollDuration).toList();
                // a json query carries no type filter, so it can match an entity that has no path
                List<Path> paths =
                        selected.stream().map(entity -> ((Document) entity).getPath()).filter(Objects::nonNull)
                                .toList();
                ctx.emit(paths);
                nbEmitted += paths.size();
            } while (!selected.isEmpty() && !ctx.isCancelled());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            // released here too when a page or an emit throws, so a cancelled or failed run does not leave an
            // open scroll context pinning segments on the cluster
            try {
                search.clearScroll();
            } catch (IOException e) {
                logger.warn("cannot clear the scroll of the {} stage", Stage.SCANQUERY, e);
            }
        }
        logger.info("selected {} paths", nbEmitted);
        return new StageResult(nbEmitted, 0, 0, 0);
    }

    private void checkRunIsUsable(PropertiesProvider properties, String searchQuery) {
        if (searchQuery == null) {
            throw new IllegalArgumentException(
                    "%s selects the files to re-extract, so it needs --searchQuery".formatted(Stage.SCANQUERY));
        }
        PipelineHelper pipeline = new PipelineHelper(properties);
        Stage nextStage = pipeline.getNextStage(Stage.SCANQUERY);
        if (!PATH_CONSUMING_STAGES.contains(nextStage)) {
            throw new IllegalArgumentException(
                    "%s enqueues file paths, which %s does not drain: expected one of %s next in --stages".formatted(
                            Stage.SCANQUERY, nextStage, PATH_CONSUMING_STAGES));
        }
        properties.get(REPORT_NAME_OPT).ifPresent(reportName -> logger.warn(
                "--reportName {} is set: the INDEX stage skips paths already recorded as extracted, so this " +
                "re-extraction may extract nothing. Drop it to force it.", reportName));
        if (!pipeline.stages.contains(Stage.DEDUPLICATE)) {
            logger.warn("{} is not in --stages: a container matched by several embedded documents will be extracted " +
                        "once per match. Add it to collapse the duplicates.", Stage.DEDUPLICATE);
        }
    }
}
