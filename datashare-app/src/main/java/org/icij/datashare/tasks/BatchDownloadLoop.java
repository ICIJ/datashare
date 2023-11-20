package org.icij.datashare.tasks;

import com.google.inject.Inject;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.cli.DatashareCliOptions;
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


public class BatchDownloadLoop {
    private final Path downloadDir;
    private final int ttlHour;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final BlockingQueue<BatchDownload> batchDownloadQueue;
    private final TaskFactory factory;
    private final TaskSupplier taskSupplier;
    private static final BatchDownload NULL_BATCH_DOWNLOAD = BatchDownload.nullObject();

    @Inject
    public BatchDownloadLoop(PropertiesProvider propertiesProvider, BlockingQueue<BatchDownload> batchDownloadQueue, TaskFactory factory, TaskSupplier taskSupplier) {
        this.batchDownloadQueue = batchDownloadQueue;
        this.factory = factory;
        this.taskSupplier = taskSupplier;
        downloadDir = Paths.get(propertiesProvider.getProperties().getProperty(DatashareCliOptions.BATCH_DOWNLOAD_DIR));
        ttlHour = Integer.parseInt(propertiesProvider.getProperties().getProperty(DatashareCliOptions.BATCH_DOWNLOAD_ZIP_TTL));
    }

    public void run() {
        logger.info("Datashare running in batch mode. Waiting batch from supplier ({})", taskSupplier.getClass());
        BatchDownload currentTask = null;
        MonitorableFutureTask<File> fileMonitorableFutureTask = null;
        while (!NULL_BATCH_DOWNLOAD.equals(currentTask)) {
            try {
                currentTask = batchDownloadQueue.poll(60, TimeUnit.SECONDS);
                createDownloadCleaner(downloadDir, ttlHour).run();

                HashMap<String, Object> taskProperties = new HashMap<>();
                taskProperties.put("batchDownload", currentTask);

                if (currentTask != null && !NULL_BATCH_DOWNLOAD.equals(currentTask)) {
                    fileMonitorableFutureTask = new MonitorableFutureTask<>(
                            factory.createDownloadRunner(currentTask, taskSupplier::progress), taskProperties);
                    fileMonitorableFutureTask.run();
                    File file = fileMonitorableFutureTask.get();
                    taskSupplier.result(currentTask.toString(), file);
                }
            } catch (Exception ex) {
                logger.error(String.format("error in loop for task %s", currentTask), ex);
                if (currentTask != null) {
                    taskSupplier.error(new TaskView<>(fileMonitorableFutureTask).id, ex);
                }
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
