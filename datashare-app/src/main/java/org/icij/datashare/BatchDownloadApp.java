package org.icij.datashare;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.icij.datashare.asynctasks.TaskFactory;
import org.icij.datashare.asynctasks.TaskSupplier;
import org.icij.datashare.asynctasks.TaskWorkerLoop;
import org.icij.datashare.tasks.BatchDownloadCleaner;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_TASK_PROGRESS_INTERVAL_SECONDS;
import static org.icij.datashare.cli.DatashareCliOptions.TASK_PROGRESS_INTERVAL_OPT;

@Singleton
public class BatchDownloadApp {
    private final ScheduledExecutorService cleanupScheduler;
    private final TaskFactory taskFactory;
    private final TaskSupplier taskSupplier;
    private final double progressMinIntervalS;

    @Inject
    public BatchDownloadApp(BatchDownloadCleaner cleaner, TaskFactory taskFactory, TaskSupplier taskSupplier, PropertiesProvider propertiesProvider) {
        this.taskFactory = taskFactory;
        this.taskSupplier = taskSupplier;
        this.progressMinIntervalS = (double) ofNullable(propertiesProvider.getProperties().get(TASK_PROGRESS_INTERVAL_OPT))
                .orElse(DEFAULT_TASK_PROGRESS_INTERVAL_SECONDS);
        this.cleanupScheduler = scheduleCleanup(cleaner);
    }

    public void start() throws Exception {
        TaskWorkerLoop workerLoop = new TaskWorkerLoop(taskFactory, taskSupplier, progressMinIntervalS);
        try {
            workerLoop.call();
        } finally {
            cleanupScheduler.shutdown();
        }
    }

    static ScheduledExecutorService scheduleCleanup(BatchDownloadCleaner cleaner) {
        long period = cleaner.tickPeriodSeconds();
        if (period == 0) {
            ScheduledExecutorService noop = Executors.newSingleThreadScheduledExecutor();
            noop.shutdown();
            return noop;
        }
        return scheduleCleanup(cleaner, period);
    }

    static ScheduledExecutorService scheduleCleanup(Runnable cleaner, long periodSeconds) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(cleaner, 0, periodSeconds, TimeUnit.SECONDS);
        return scheduler;
    }
}
