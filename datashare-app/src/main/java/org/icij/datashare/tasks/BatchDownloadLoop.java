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
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;


public class BatchDownloadLoop implements Callable<Integer> {
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

    public Integer call() {
        logger.info("Waiting batch downloads from supplier ({})", taskSupplier.getClass());
        TaskView<File> currentTask = null;
        int nbTasks = 0;
        while (!POISON.equals(currentTask)) {
            try {
                currentTask = taskSupplier.get(60, TimeUnit.SECONDS);
                createDownloadCleaner(downloadDir, ttlHour).run();
                nbTasks++;

                if (currentTask != null && !POISON.equals(currentTask)) {
                    BatchDownloadRunner downloadRunner = factory.createDownloadRunner(currentTask, taskSupplier::progress);
                    File zipResult = downloadRunner.call();
                    taskSupplier.result(currentTask.id, zipResult);
                }
            } catch (Throwable ex) {
                logger.error(String.format("error in loop for task %s", currentTask), ex);
                if (currentTask != null && !currentTask.isNull()) {
                    taskSupplier.error(currentTask.id, ex);
                }
            }
        }
        return nbTasks;
    }

    public BatchDownloadCleaner createDownloadCleaner(Path downloadDir, int delaySeconds) {
        return new BatchDownloadCleaner(downloadDir, delaySeconds);
    }
}
