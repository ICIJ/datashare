package org.icij.datashare;

import static java.lang.Integer.parseInt;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.asynctasks.ConductorUtils.buildConductorExecutor;
import static org.icij.datashare.asynctasks.ConductorUtils.declareTasksAndWorkflows;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_QUEUE_TYPE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_TASK_WORKERS;
import static org.icij.datashare.cli.DatashareCliOptions.TASK_WORKERS_OPT;
import static org.icij.datashare.tasks.TaskManagerConductor.globResources;
import static org.icij.datashare.tasks.Utils.getRoutingKey;

import com.netflix.conductor.client.http.ConductorClient;
import com.netflix.conductor.sdk.workflow.executor.WorkflowExecutor;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.icij.datashare.asynctasks.TaskSupplier;
import org.icij.datashare.asynctasks.TaskWorkerLoop;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class TaskWorkerApp {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskWorkerApp.class);

    public static void start(Properties properties) throws Exception {
        int taskWorkersNb =
            parseInt((String) ofNullable(properties.get(TASK_WORKERS_OPT)).orElse(DEFAULT_TASK_WORKERS));
        LOGGER.info("start {} task workers", taskWorkersNb);
        Thread.sleep(5000);

        try (CommonMode mode = CommonMode.create(properties)) {
            // It's impossible to implement the TaskSupplierConductor, the TaskSupplier API make so sense in conductor
            // as conductor itself is handling the worker lifecycle (receive message, handle error, etc...)
            //
            //  Here we hence just launch a conductor WorkflowExecutor (worker)
            if (Optional.ofNullable(properties.get(BATCH_QUEUE_TYPE_OPT)).map(q -> ((String) q).toUpperCase())
                .orElse("")
                .equals(QueueType.CONDUCTOR.name().toUpperCase())) {
                WorkflowExecutor workerExecutor = buildConductorExecutor(mode.get(DatashareTaskFactory.class),
                    mode.get(ConductorClient.class), getRoutingKey(new PropertiesProvider(properties)), taskWorkersNb
                );
                // TODO: declaring wf could/should also be done on the TM side
                List<Path> taskDeclarationPaths = globResources("conductor/tasks", "*.json");
                List<Path> wfDeclarationPaths = globResources("conductor/workflows", "*.json");
                declareTasksAndWorkflows(workerExecutor, taskDeclarationPaths, wfDeclarationPaths);
                try {
                    workerExecutor.initWorkers("");
                    Thread.currentThread().join();
                } catch (InterruptedException e) {
                    LOGGER.info("ask for termination, shutting down...");
                } finally {
                    workerExecutor.shutdown();
                }
            } else {
                ExecutorService executorService = Executors.newFixedThreadPool(taskWorkersNb);
                List<TaskWorkerLoop> workers = IntStream.range(0, taskWorkersNb).mapToObj(
                        i -> new TaskWorkerLoop(mode.get(DatashareTaskFactory.class), mode.get(TaskSupplier.class)))
                    .toList();
                workers.forEach(executorService::submit);
                // to wait consumers, else we are closing them all
                executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
            }
        }
    }

}
