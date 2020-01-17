package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.extract.extractor.ExtractionStatus;
import org.icij.extract.report.Report;
import org.icij.extract.report.ReportMap;
import org.icij.task.DefaultTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import static java.lang.Integer.parseInt;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

public class ScanIndexTask extends DefaultTask<Long> implements UserTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final int scrollSize;
    private final String projectName;
    private final ReportMap reportMap;
    private final User user;

    @Inject
    public ScanIndexTask(DocumentCollectionFactory factory, final Indexer indexer, final PropertiesProvider propertiesProvider, @Assisted User user) {
        this.user = user;
        this.scrollSize = parseInt(propertiesProvider.get("scrollSize").orElse("1000"));
        this.projectName = propertiesProvider.get("defaultProject").orElse("localœ-datashare");
        Optional<String> reportName = propertiesProvider.get("reportName");
        this.reportMap = reportName.map(s -> factory.createMap(propertiesProvider, reportName.get())).
                orElseThrow(() -> new IllegalArgumentException("no reportName property defined"));
        this.indexer = indexer;
    }

    @Override
    public Long call() throws Exception {
        logger.info("scanning index {} with scroll size {}", projectName, scrollSize);
        Indexer.Searcher search = indexer.search(projectName, Document.class).withSource("path").limit(scrollSize);
        List<? extends Entity> docsToProcess;
        long nbProcessed = 0;
        do {
            docsToProcess = search.scroll().collect(toList());
            reportMap.putAll(docsToProcess.stream().map(d -> ((Document) d).getPath()).collect(toMap(p -> p, p -> new Report(ExtractionStatus.SUCCESS))));
            nbProcessed += docsToProcess.size();
        } while (docsToProcess.size() != 0);
        logger.info("imported {} paths into {}", nbProcessed, reportMap);
        reportMap.close();
        return nbProcessed;
    }

    @Override
    public User getUser() {
        return user;
    }
}
