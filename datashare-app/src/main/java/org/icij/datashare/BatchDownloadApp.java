package org.icij.datashare;

import org.icij.datashare.asynctasks.TaskFactory;
import org.icij.datashare.asynctasks.TaskSupplier;
import org.icij.datashare.asynctasks.TaskWorkerLoop;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.BatchDownloadCleaner;
import org.icij.datashare.text.indexing.Indexer;
import org.redisson.api.RedissonClient;

import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_TASK_PROGRESS_INTERVAL_SECONDS;
import static org.icij.datashare.cli.DatashareCliOptions.TASK_PROGRESS_INTERVAL_OPT;


public class BatchDownloadApp {
    public static void start(Properties properties) throws Exception {
        CommonMode commonMode = CommonMode.create(properties);
        double progressMinIntervalS = (double) ofNullable(properties.get(TASK_PROGRESS_INTERVAL_OPT))
                .orElse(DEFAULT_TASK_PROGRESS_INTERVAL_SECONDS);
        TaskWorkerLoop taskWorkerLoop = new TaskWorkerLoop(commonMode.get(TaskFactory.class), commonMode.get(TaskSupplier.class), progressMinIntervalS);
        start(commonMode.get(BatchDownloadCleaner.class), taskWorkerLoop);
        commonMode.get(Indexer.class).close();
        commonMode.get(RedissonClient.class).shutdown();
    }

    static void start(Runnable cleaner, Callable<Integer> workerLoop) throws Exception {
        ScheduledExecutorService cleanupScheduler = scheduleCleanup(cleaner);
        try {
            workerLoop.call();
        } finally {
            cleanupScheduler.shutdown();
        }
    }

    static ScheduledExecutorService scheduleCleanup(Runnable cleaner) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(cleaner, 0, 60, TimeUnit.SECONDS);
        return scheduler;
    }
}
