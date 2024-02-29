package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.Entity;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.extract.extractor.ExtractionStatus;
import org.icij.extract.report.Report;
import org.icij.extract.report.ReportMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import org.icij.datashare.extract.DocumentCollectionFactory;

import static java.lang.Integer.parseInt;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.icij.datashare.PropertiesProvider.MAP_NAME_OPTION;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_SIZE;

public class ScanIndexTask extends PipelineTask<Path> {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final int scrollSize;
    private final String projectName;
    private final ReportMap reportMap;
    private final int scrollSlices;

    @Inject
    public ScanIndexTask(DocumentCollectionFactory<Path> factory, final Indexer indexer,
                         @Assisted TaskView<Long> taskView, @Assisted BiFunction<String, Double, Void> updateCallback) {
        super(Stage.SCANIDX, taskView.user, new PipelineHelper(new PropertiesProvider(taskView.properties)).getQueueNameFor(Stage.SCANIDX), factory, new PropertiesProvider(taskView.properties), Path.class);
        this.scrollSize = parseInt(propertiesProvider.get(SCROLL_SIZE).orElse("1000"));
        this.scrollSlices = parseInt(propertiesProvider.get("scrollSlices").orElse("1"));
        this.projectName = propertiesProvider.get("defaultProject").orElse("local-datashare");
        String reportName = propertiesProvider.get(MAP_NAME_OPTION).orElse("extract:report");
        this.reportMap = factory.createMap(reportName);
        this.indexer = indexer;
    }

    @Override
    public Long call() throws Exception {
        logger.info("scanning index {} with scroll size {} and {} slices", projectName, scrollSize, scrollSlices);
        Optional<Long> nb = IntStream.range(0, scrollSlices).parallel().mapToObj(this::slicedScroll).reduce(Long::sum);
        logger.info("imported {} paths into {}", nb.get(), reportMap);
        return nb.get();
    }

    private Long slicedScroll(int sliceNum) {
        Indexer.Searcher search = indexer.search(singletonList(projectName), Document.class).withSource("path").limit(scrollSize);
        List<? extends Entity> docsToProcess = new ArrayList<>();
        long nbProcessed = 0;
        do {
            try {
                docsToProcess = search.scroll(sliceNum, scrollSlices).collect(toList());
                reportMap.putAll(docsToProcess.stream().map(d -> ((Document) d).getPath()).collect(toMap(p -> p, p -> new Report(ExtractionStatus.SUCCESS), (a, b) -> b)));
                nbProcessed += docsToProcess.size();
            } catch (IOException e) {
                logger.error("error in slice {}", sliceNum, e);
            }
        } while (!docsToProcess.isEmpty());
        return nbProcessed;
    }
}
