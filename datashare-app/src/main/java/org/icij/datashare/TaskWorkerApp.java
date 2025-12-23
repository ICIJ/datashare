package org.icij.datashare;

import org.icij.datashare.mode.CommonMode;

public class TaskWorkerApp {
    public static void start(CommonMode mode) throws Exception {
        mode.runTemporalWorkers();
    }
}