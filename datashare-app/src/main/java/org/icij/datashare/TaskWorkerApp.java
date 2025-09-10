package org.icij.datashare;

import org.icij.datashare.asynctasks.TaskSupplier;
import org.icij.datashare.asynctasks.TaskWorkerLoop;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static java.lang.Integer.parseInt;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_TASK_PROGRESS_INTERVAL_SECONDS;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_TASK_WORKERS;
import static org.icij.datashare.cli.DatashareCliOptions.TASK_PROGRESS_INTERVAL_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.TASK_WORKERS_OPT;


public class TaskWorkerApp {
    public static void start(CommonMode mode) throws Exception {
        ExecutorService workers = mode.createWorkers();
        workers.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS); // to wait consumers, else we are closing them all
    }
}