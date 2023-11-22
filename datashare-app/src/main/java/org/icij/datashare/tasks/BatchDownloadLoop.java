package org.icij.datashare.tasks;

import com.google.inject.Inject;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCliOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;


public class BatchDownloadLoop {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Path downloadDir;
    private final int ttlHour;
    private final TaskFactory factory;
    private final TaskSupplier taskSupplier;
    public static final TaskView<Serializable> POISON = TaskView.nullObject();

    @Inject
    public BatchDownloadLoop(PropertiesProvider propertiesProvider, TaskFactory factory, TaskSupplier taskSupplier) {
        this.factory = factory;
        this.taskSupplier = taskSupplier;
        downloadDir = Paths.get(propertiesProvider.getProperties().getProperty(DatashareCliOptions.BATCH_DOWNLOAD_DIR));
        ttlHour = Integer.parseInt(propertiesProvider.getProperties().getProperty(DatashareCliOptions.BATCH_DOWNLOAD_ZIP_TTL));
    }

    public void run() {
        logger.info("Datashare running in batch mode. Waiting batch from supplier ({})", taskSupplier.getClass());
        TaskView<File> currentTask = null;
        while (!POISON.equals(currentTask)) {
            try {
                currentTask = taskSupplier.get(60, TimeUnit.SECONDS);
                createDownloadCleaner(downloadDir, ttlHour).run();

                if (currentTask != null && !POISON.equals(currentTask)) {
                    BatchDownloadRunner downloadRunner = factory.createDownloadRunner(currentTask, taskSupplier::progress);
                    File zipResult = downloadRunner.call();
                    taskSupplier.result(currentTask.toString(), zipResult);
                }
            } catch (Exception ex) {
                logger.error(String.format("error in loop for task %s", currentTask), ex);
                if (currentTask != null && currentTask.id != null) {
                    taskSupplier.error(currentTask.id, ex);
                }
            }
        }
    }

    public BatchDownloadCleaner createDownloadCleaner(Path downloadDir, int delaySeconds) {
        return new BatchDownloadCleaner(downloadDir, delaySeconds);
    }
}
