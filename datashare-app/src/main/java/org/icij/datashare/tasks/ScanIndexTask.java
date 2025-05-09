package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.function.Function;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.extract.extractor.ExtractionStatus;
import org.icij.extract.report.Report;
import org.icij.extract.report.ReportMap;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static java.lang.Integer.parseInt;
import static java.lang.String.valueOf;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_DURATION;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_SIZE;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_SLICES;
import static org.icij.datashare.cli.DatashareCliOptions.REPORT_NAME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_DURATION_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_SIZE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_SLICES_OPT;
import org.icij.datashare.asynctasks.TaskGroupType;
import static org.icij.datashare.text.indexing.ScrollQueryBuilder.createScrollQuery;

@TaskGroup(TaskGroupType.Java)
public class ScanIndexTask extends PipelineTask<Path> {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final String projectName;
    private final ReportMap reportMap;
    private final String scrollDuration;
    private final int scrollSize;
    private final int scrollSlices;

    @Inject
    public ScanIndexTask(DocumentCollectionFactory<Path> factory, final Indexer indexer,
                         @Assisted Task taskView, @Assisted Function<Double, Void> ignored) {
        super(Stage.SCANIDX, taskView.getUser(), factory, new PropertiesProvider(taskView.args), Path.class);
        this.scrollDuration = propertiesProvider.get(SCROLL_DURATION_OPT).orElse(DEFAULT_SCROLL_DURATION);
        this.scrollSize = parseInt(propertiesProvider.get(SCROLL_SIZE_OPT).orElse(valueOf(DEFAULT_SCROLL_SIZE)));
        this.scrollSlices = parseInt(propertiesProvider.get(SCROLL_SLICES_OPT).orElse(valueOf(DEFAULT_SCROLL_SLICES)));
        this.projectName = propertiesProvider.get(DEFAULT_PROJECT_OPT).orElse(DEFAULT_DEFAULT_PROJECT);
        this.reportMap = factory.createMap(getMapName());
        this.indexer = indexer;
    }

    @Override
    public Long runTask() throws Exception {
        super.runTask();
        logger.info("scanning index {} with {} scroll, scroll size {} and {} slice(s)", projectName, scrollDuration, scrollSize, scrollSlices);
        Optional<Long> nb = IntStream.range(0, scrollSlices).parallel().mapToObj(this::slicedScroll).reduce(Long::sum);
        logger.info("imported {} paths into map {}", nb.get(), getMapName());
        return nb.get();
    }

    private Long slicedScroll(int sliceNum) {
        Indexer.Searcher search = indexer.search(singletonList(projectName), Document.class).withSource("path").limit(scrollSize);
        List<? extends Entity> docsToProcess = new ArrayList<>();
        long nbProcessed = 0;
        do {
            try {
                docsToProcess = search.scroll(createScrollQuery().withDuration(scrollDuration).withSlices(sliceNum, scrollSlices).build()).collect(toList());
                reportMap.putAll(docsToProcess.stream().map(d -> ((Document) d).getPath()).collect(toMap(p -> p, p -> new Report(ExtractionStatus.SUCCESS), (a, b) -> b)));
                nbProcessed += docsToProcess.size();
            } catch (IOException e) {
                logger.error("error in slice {}", sliceNum, e);
            }
        } while (!docsToProcess.isEmpty());
        return nbProcessed;
    }

    @NotNull
    private String getMapName() {
        return propertiesProvider.get(REPORT_NAME_OPT).orElse("extract:report");
    }
}
