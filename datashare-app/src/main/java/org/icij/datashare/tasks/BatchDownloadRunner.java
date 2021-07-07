package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.monitoring.Monitorable;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.lang.Integer.min;
import static java.lang.Integer.parseInt;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_SEARCH_THROTTLE;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_SIZE;

public class BatchDownloadRunner  implements Callable<Integer>, Monitorable, UserTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    static final int MAX_SCROLL_SIZE = 3500;
    static final int MAX_BATCH_RESULT_SIZE = 10000;
    volatile int docsToProcessSize = 0;
    private final AtomicInteger numberOfResults = new AtomicInteger(0);

    private final ElasticsearchIndexer indexer;
    private final PropertiesProvider propertiesProvider;
    private final User user;
    private final BatchDownload batchDownload;

    @Inject
    public BatchDownloadRunner(ElasticsearchIndexer indexer, PropertiesProvider propertiesProvider,
                               @Assisted User user, @Assisted BatchDownload batchDownload) {
        this.indexer = indexer;
        this.propertiesProvider = propertiesProvider;
        this.user = user;
        this.batchDownload = batchDownload;
    }

    @Override
    public Integer call() throws Exception {
        int throttleMs = parseInt(propertiesProvider.get(BATCH_SEARCH_THROTTLE).orElse("0"));
        int scrollSize = min(parseInt(propertiesProvider.get(SCROLL_SIZE).orElse("1000")), MAX_SCROLL_SIZE);

        logger.info("running batch download for user {} on project {} with throttle {}ms and scroll size of {}",
                user.getId(), batchDownload.project, throttleMs, scrollSize);
        Indexer.Searcher searcher = indexer.search(batchDownload.project.getId(), Document.class).
                with(batchDownload.query).withoutSource("content").limit(scrollSize);
        List<? extends Entity> docsToProcess = searcher.scroll().collect(toList());
        if (docsToProcess.size() == 0) return 0;
        docsToProcessSize = docsToProcess.size();

        try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(batchDownload.filename.toFile()))) {
            while (docsToProcess.size() != 0 && numberOfResults.get() < MAX_BATCH_RESULT_SIZE - MAX_SCROLL_SIZE) {
                for (Entity doc : docsToProcess) {
                    try (InputStream from = new SourceExtractor().getSource(batchDownload.project, (Document) doc)) {
                        zipOutputStream.putNextEntry(new ZipEntry(getEntryName((Document) doc)));
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = from.read(buffer)) > 0) {
                            zipOutputStream.write(buffer, 0, len);
                        }
                        zipOutputStream.closeEntry();
                        numberOfResults.incrementAndGet();
                    }
                }
                docsToProcess = searcher.scroll().collect(toList());
            }
        }
        logger.info("created batch download file {} ({} bytes/{} entries) for user {}",
                batchDownload.filename, Files.size(batchDownload.filename), numberOfResults, user.getId());
        return numberOfResults.get();
    }

    @NotNull
    private String getEntryName(Document doc) {
        return doc.getPath().isAbsolute() ? doc.getPath().toString().substring(1): doc.getPath().toString();
    }

    @Override
    public double getProgressRate() {
        return docsToProcessSize == 0 ? 0 : (double) numberOfResults.get()/docsToProcessSize;
    }

    @Override public User getUser() { return user; }
}
