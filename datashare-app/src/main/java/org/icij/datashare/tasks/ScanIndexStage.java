package org.icij.datashare.tasks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageActivityOptions;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageProcessor;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.extract.extractor.ExtractionStatus;
import org.icij.extract.report.Report;
import org.icij.extract.report.ReportMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static java.lang.Integer.parseInt;
import static java.lang.String.valueOf;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.PropertiesProvider.REPORT_NAME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_DURATION;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_SIZE;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_SLICES;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_DURATION_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_SIZE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_SLICES_OPT;
import static org.icij.datashare.text.indexing.ScrollQueryBuilder.createScrollQuery;

/**
 * Source stage: records every path already in the index in the report map ({@code --reportName}), the set INDEX reads
 * to skip what it already extracted. It emits nothing, so it is of no use to a workflow that chains stages by what
 * they emit: it belongs to the queues. The map is given as a factory (the document collection factory of the
 * process), as the stage context has none.
 */
public class ScanIndexStage implements StageProcessor<Void, Void> {
    private static final StageActivityOptions ACTIVITY_OPTIONS = new StageActivityOptions(Duration.ofDays(7), 0);
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final Function<String, ReportMap> mapFactory;

    public ScanIndexStage(Indexer indexer, Function<String, ReportMap> mapFactory) {
        this.indexer = indexer;
        this.mapFactory = mapFactory;
    }

    @Override
    public Stage stage() {
        return Stage.SCANIDX;
    }

    @Override
    public Class<Void> itemType() {
        return Void.class;
    }

    @Override
    public StageActivityOptions activityOptions() {
        return ACTIVITY_OPTIONS;
    }

    @Override
    public StageResult process(List<Void> batch, StageContext<Void> ctx) {
        PropertiesProvider properties = new PropertiesProvider(ctx.options());
        String projectName = ctx.project().getName();
        String scrollDuration = properties.get(SCROLL_DURATION_OPT).orElse(DEFAULT_SCROLL_DURATION);
        int scrollSize = parseInt(properties.get(SCROLL_SIZE_OPT).orElse(valueOf(DEFAULT_SCROLL_SIZE)));
        int scrollSlices = parseInt(properties.get(SCROLL_SLICES_OPT).orElse(valueOf(DEFAULT_SCROLL_SLICES)));
        String mapName = properties.get(REPORT_NAME_OPT).orElse("extract:report");
        ReportMap reportMap = mapFactory.apply(mapName);
        logger.info("scanning index {} with {} scroll, scroll size {}, {} slice(s) in parallel", projectName,
                    scrollDuration, scrollSize, scrollSlices);
        // the slices are independent scrolls of the same index: the parallelism is inside the one stage run
        long nb = IntStream.range(0, scrollSlices).parallel().mapToLong(
                slice -> slicedScroll(projectName, scrollDuration, scrollSize, slice, scrollSlices, reportMap)).sum();
        logger.info("imported {} paths into map {}", nb, mapName);
        return new StageResult(nb, 0, 0, 0);
    }

    private long slicedScroll(String projectName, String scrollDuration, int scrollSize, int sliceNum, int scrollSlices,
                              ReportMap reportMap) {
        Indexer.Searcher search =
                indexer.search(singletonList(projectName), Document.class).withDocValues("path").limit(scrollSize)
                       .sort("_doc", Indexer.Searcher.SortOrder.ASC);
        List<? extends Entity> docsToProcess = new ArrayList<>();
        long nbProcessed = 0;
        do {
            try {
                docsToProcess = search.scroll(createScrollQuery().withDuration(scrollDuration)
                                                                 .withSlices(sliceNum, scrollSlices).build())
                                      .collect(toList());
                addToReportMap(reportMap, docsToProcess, scrollSize);
                nbProcessed += docsToProcess.size();
            } catch (IOException e) {
                // the previous page is still in docsToProcess, so logging and carrying on re-enters
                // the loop with the same non-empty list and scrolls forever on a broken scroll
                throw new UncheckedIOException("scroll failed in slice %d".formatted(sliceNum), e);
            }
        } while (!docsToProcess.isEmpty());
        return nbProcessed;
    }

    /** One putAll per scroll page: the batch size is the scroll size. */
    private void addToReportMap(ReportMap reportMap, List<? extends Entity> docs, int batchSize) {
        Report successReport = new Report(ExtractionStatus.SUCCESS);
        Map<Path, Report> batch = new HashMap<>();
        for (Entity entity : docs) {
            batch.put(((Document) entity).getPath(), successReport);
            if (batch.size() >= batchSize) {
                reportMap.putAll(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            reportMap.putAll(batch);
        }
    }
}
