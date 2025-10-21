package org.icij.datashare;

import org.icij.datashare.mode.CommonMode;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class TaskWorkerApp {
    public static void start(CommonMode mode) throws Exception {
        ExecutorService workers = mode.createWorkers();
        workers.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS); // to wait consumers, else we are closing them all
    }
}