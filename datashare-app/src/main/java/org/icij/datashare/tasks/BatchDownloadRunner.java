package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.Entity;
import org.icij.datashare.HumanReadableSize;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.monitoring.Monitorable;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ExtractException;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.extract.extractor.EmbeddedDocumentMemoryExtractor.ContentNotFoundException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import static java.lang.Integer.min;
import static java.lang.Integer.parseInt;
import static java.lang.String.valueOf;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.cli.DatashareCliOptions.*;

public class BatchDownloadRunner implements Callable<File>, Monitorable, UserTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    static final int MAX_SCROLL_SIZE = 3500;
    static final int MAX_BATCH_RESULT_SIZE = 10000;
    volatile long docsToProcessSize = 0;
    private final AtomicInteger numberOfResults = new AtomicInteger(0);

    private final Indexer indexer;
    private final PropertiesProvider propertiesProvider;
    private final BatchDownload batchDownload;
    private final Function<TaskView<File>, Void> updateCallback;

    @Inject
    public BatchDownloadRunner(Indexer indexer, PropertiesProvider propertiesProvider, @Assisted BatchDownload batchDownload, @Assisted Function<TaskView<File>, Void> updateCallback) {
        this.indexer = indexer;
        this.propertiesProvider = propertiesProvider;
        this.batchDownload = batchDownload;
        this.updateCallback = updateCallback;
    }

    @Override
    public File call() throws Exception {
        int throttleMs = parseInt(propertiesProvider.get(BATCH_THROTTLE).orElse("0"));
        int maxResultSize = parseInt(propertiesProvider.get(BATCH_DOWNLOAD_MAX_NB_FILES).orElse(valueOf(MAX_BATCH_RESULT_SIZE)));
        int scrollSize = min(parseInt(propertiesProvider.get(SCROLL_SIZE).orElse("1000")), MAX_SCROLL_SIZE);
        long maxZipSizeBytes = HumanReadableSize.parse(propertiesProvider.get(BATCH_DOWNLOAD_MAX_SIZE).orElse("100M"));
        long zippedFilesSize = 0;

        logger.info("running batch download for user {} on project {} with throttle {}ms and scroll size of {}",
                batchDownload.user.getId(), batchDownload.project, throttleMs, scrollSize);
        Indexer.Searcher searcher = indexer.search(batchDownload.project.getId(), Document.class).withoutSource("content").limit(scrollSize);
        if (batchDownload.isJsonQuery()) {
            searcher.set(batchDownload.queryAsJson());
        } else {
            searcher.with(batchDownload.query);
        }
        List<? extends Entity> docsToProcess = searcher.scroll().collect(toList());
        if (docsToProcess.size() == 0) {
            logger.warn("no results for batchDownload {}", batchDownload.uuid);
            return null;
        }
        docsToProcessSize = searcher.totalHits();
        if (docsToProcessSize > maxResultSize) {
            logger.warn("number of results for batch download > {} for {}/{} (nb zip entries will be limited)",
                    maxResultSize, batchDownload.uuid, batchDownload.user);
        }

        try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(batchDownload.filename.toFile()))) {
            HashMap<String, Object> taskProperties = new HashMap<>();
            taskProperties.put("batchDownload", batchDownload);
            while (docsToProcess.size() != 0 && numberOfResults.get() <= maxResultSize - scrollSize && zippedFilesSize < maxZipSizeBytes) {
                for (Entity doc : docsToProcess) {
                    try (InputStream from = new SourceExtractor().getSource(batchDownload.project, (Document) doc)) {
                        zipOutputStream.putNextEntry(new ZipEntry(getEntryName((Document) doc)));
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = from.read(buffer)) > 0) {
                            zipOutputStream.write(buffer, 0, len);
                            zippedFilesSize += len;
                        }
                        zipOutputStream.closeEntry();
                        numberOfResults.incrementAndGet();
                        updateCallback.apply(new TaskView<>(new MonitorableFutureTask<>(this, taskProperties)));
                    } catch (ExtractException|ZipException|ContentNotFoundException zex) {
                        logger.warn("exception during extract/zip. skipping entry for doc " + doc.getId(), zex);
                    }
                }
                docsToProcess = searcher.scroll().collect(toList());
            }
        }
        logger.info("created batch download file {} ({} bytes/{} entries) for user {}",
                batchDownload.filename, Files.size(batchDownload.filename), numberOfResults, batchDownload.user.getId());
        return batchDownload.filename.toFile();
    }

    @NotNull
    private String getEntryName(Document doc) {
        return doc.getPath().isAbsolute() ? doc.getPath().toString().substring(1) : doc.getPath().toString();
    }

    @Override
    public double getProgressRate() {
        return docsToProcessSize == 0 ? 0 : (double) numberOfResults.get() / docsToProcessSize;
    }

    @Override
    public User getUser() {
        return batchDownload.user;
    }

    @Override
    public String toString() {
        return getClass().getName() + "@" + batchDownload.uuid;
    }
}
