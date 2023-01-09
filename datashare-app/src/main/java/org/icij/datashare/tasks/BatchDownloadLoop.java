package org.icij.datashare.tasks;

import com.google.inject.Inject;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.cli.DatashareCliOptions;
import org.icij.datashare.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.singletonList;
import static org.icij.datashare.tasks.BatchDownloadRunner.*;
import static org.icij.datashare.text.Project.project;

public class BatchDownloadLoop {
    private final Path DOWNLOAD_DIR = Paths.get(System.getProperty("user.dir")).resolve("app/tmp");
    private final static String DEFAULT_BATCH_DOWNLOAD_ZIP_TTL = "24";
    private final int ttlHour;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final BlockingQueue<BatchDownload> batchDownloadQueue;
    private final TaskFactory factory;
    private final TaskManager manager;

    @Inject
    public BatchDownloadLoop(PropertiesProvider propertiesProvider, BlockingQueue<BatchDownload> batchDownloadQueue, TaskFactory factory, TaskManager manager) {
        this.batchDownloadQueue = batchDownloadQueue;
        this.factory = factory;
        this.manager = manager;
        ttlHour = Integer.parseInt(propertiesProvider.get(DatashareCliOptions.BATCH_DOWNLOAD_ZIP_TTL).orElse(DEFAULT_BATCH_DOWNLOAD_ZIP_TTL));
    }

    public void run() {
        logger.info("Datashare running in batch mode. Waiting batch from ds:batchdownload.queue ({})", batchDownloadQueue.getClass());
        BatchDownload currentBatch = null;
        while (!NULL_BATCH_DOWNLOAD.equals(currentBatch)) {
            try {
                currentBatch = batchDownloadQueue.poll(60, TimeUnit.SECONDS);
                createDownloadCleaner(DOWNLOAD_DIR, ttlHour).run();

                HashMap<String, Object> taskProperties = new HashMap<>();
                taskProperties.put("batchDownload", currentBatch);
                MonitorableFutureTask<File> fileMonitorableFutureTask = new MonitorableFutureTask<>(
                        factory.createDownloadRunner(currentBatch, manager::save), taskProperties);
                fileMonitorableFutureTask.run();
                manager.save(new TaskView<>(fileMonitorableFutureTask));
            } catch (Exception ex) {
                logger.error("error in loop", ex);
            }
        }
    }

    public void enqueuePoison() {
        batchDownloadQueue.add(NULL_BATCH_DOWNLOAD);
    }

    public void close() throws IOException {
        if (batchDownloadQueue instanceof Closeable) {
            ((Closeable) batchDownloadQueue).close();
        }
    }

    public BatchDownloadCleaner createDownloadCleaner(Path downloadDir, int delaySeconds) {
        return new BatchDownloadCleaner(downloadDir, delaySeconds);
    }
}
