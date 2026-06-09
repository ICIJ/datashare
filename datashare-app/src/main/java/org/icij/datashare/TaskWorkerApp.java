package org.icij.datashare;

import org.icij.datashare.mode.CommonMode;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class TaskWorkerApp {
    // Batch download zip files are written to the shared download directory served by the web node.
    // Cleanup is handled there (BatchDownloadApp.scheduleCleanup), not here.
    // If workers ever run with node-local storage instead of a shared volume, cleanup must be wired here too.
    public static void start(CommonMode mode) throws Exception {
        ExecutorService workers = mode.runWorkers();
        workers.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS); // to wait consumers, else we are closing them all
    }
}