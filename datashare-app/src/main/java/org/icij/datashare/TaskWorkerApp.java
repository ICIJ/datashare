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
    static Logger logger = LoggerFactory.getLogger(TaskWorkerApp.class);

    public static void start(Properties properties) throws Exception {
        int taskWorkersNb = parseInt((String) ofNullable(properties.get(TASK_WORKERS_OPT)).orElse(DEFAULT_TASK_WORKERS));

        try (CommonMode mode = CommonMode.create(properties)) {
            ExecutorService executorService = Executors.newFixedThreadPool(taskWorkersNb);
            double progressMinIntervalS = ofNullable(properties.getProperty(TASK_PROGRESS_INTERVAL_OPT))
                    .map(Double::parseDouble)
                    .orElse(DEFAULT_TASK_PROGRESS_INTERVAL_SECONDS);
            List<TaskWorkerLoop> workers = IntStream.range(0, taskWorkersNb).mapToObj(i -> new TaskWorkerLoop(mode.get(DatashareTaskFactory.class), mode.get(TaskSupplier.class), progressMinIntervalS)).toList();
            workers.forEach(executorService::submit);
            Runtime.getRuntime().addShutdownHook(closeThread(workers));
            executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS); // to wait consumers, else we are closing them all
        }
    }

    private static Thread closeThread(List<TaskWorkerLoop> workers) {
        return new Thread(() -> {
            logger.info("main shutdown hook is gracefully closing worker loop");
            for (TaskWorkerLoop worker : workers) {
                try {
                    worker.close();
                } catch (IOException e) {
                    logger.error("Error closing worker", e);
                }
            }
        });
    }
}
