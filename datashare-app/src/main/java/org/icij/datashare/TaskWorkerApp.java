package org.icij.datashare;

import org.icij.datashare.asynctasks.TaskSupplier;
import org.icij.datashare.asynctasks.TaskWorkerLoop;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.DatashareTaskFactory;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static java.lang.Integer.parseInt;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_TASK_WORKERS;
import static org.icij.datashare.cli.DatashareCliOptions.TASK_WORKERS_OPT;


public class TaskWorkerApp {
    public static void start(Properties properties) throws Exception {
        int taskWorkersNb = parseInt((String) ofNullable(properties.get(TASK_WORKERS_OPT)).orElse(DEFAULT_TASK_WORKERS));

        try (CommonMode mode = CommonMode.create(properties)) {
            ExecutorService executorService = Executors.newFixedThreadPool(taskWorkersNb);
            List<TaskWorkerLoop> workers = IntStream.range(0, taskWorkersNb).mapToObj(i -> new TaskWorkerLoop(mode.get(DatashareTaskFactory.class), mode.get(TaskSupplier.class))).toList();
            workers.forEach(executorService::submit);
            executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS); // to wait consumers, else we are closing them all
        }
    }
}
