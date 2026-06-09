package org.icij.datashare;

import org.icij.datashare.tasks.BatchDownloadCleaner;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BatchDownloadApp {
    private BatchDownloadApp() {}

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
        scheduler.execute(cleaner);
        scheduler.scheduleAtFixedRate(cleaner, periodSeconds, periodSeconds, TimeUnit.SECONDS);
        return scheduler;
    }
}
