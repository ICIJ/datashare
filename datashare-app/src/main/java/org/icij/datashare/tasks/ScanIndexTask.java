package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
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
import java.util.Properties;
import java.util.stream.IntStream;
import org.icij.datashare.extract.DocumentCollectionFactory;

import static java.lang.Integer.parseInt;
import static java.lang.String.valueOf;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.icij.datashare.cli.DatashareCliOptions.*;
import static org.icij.datashare.text.indexing.ScrollQueryBuilder.createScrollQuery;

public class ScanIndexTask extends PipelineTask<Path> implements UserTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final String projectName;
    private final ReportMap reportMap;
    private final User user;
    private final String scrollDuration;
    private final int scrollSize;
    private final int scrollSlices;

    @Inject
    public ScanIndexTask(DocumentCollectionFactory<Path> factory, final Indexer indexer, @Assisted User user, @Assisted Properties properties) {
        super(Stage.SCANIDX, user, factory, new PropertiesProvider(properties), Path.class);
        this.user = user;
        this.scrollDuration = propertiesProvider.get(SCROLL_DURATION_OPT).orElse(DEFAULT_SCROLL_DURATION);
        this.scrollSize = parseInt(propertiesProvider.get(SCROLL_SIZE_OPT).orElse(valueOf(DEFAULT_SCROLL_SIZE)));
        this.scrollSlices = parseInt(propertiesProvider.get(SCROLL_SLICES_OPT).orElse(valueOf(DEFAULT_SCROLL_SLICES)));
        this.projectName = propertiesProvider.get(DEFAULT_PROJECT_OPT).orElse(DEFAULT_DEFAULT_PROJECT);
        this.reportMap = factory.createMap(propertiesProvider.get(REPORT_NAME_OPT).orElse("extract:report"));
        this.indexer = indexer;
    }

    @Override
    public Long call() throws Exception {
        logger.info("scanning index {} with {} scroll, scroll size {} and {} slices", projectName, scrollDuration,scrollSize, scrollSlices);
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
                docsToProcess = search.scroll(createScrollQuery().withDuration(scrollDuration).withSlices(sliceNum, scrollSlices).build()).collect(toList());
                reportMap.putAll(docsToProcess.stream().map(d -> ((Document) d).getPath()).collect(toMap(p -> p, p -> new Report(ExtractionStatus.SUCCESS), (a, b) -> b)));
                nbProcessed += docsToProcess.size();
            } catch (IOException e) {
                logger.error("error in slice {}", sliceNum, e);
            }
        } while (!docsToProcess.isEmpty());
        return nbProcessed;
    }

    @Override
    public User getUser() {
        return user;
    }
}
