package org.icij.datashare.tasks.temporal;

import static org.icij.datashare.LambdaExceptionUtils.rethrowConsumer;

import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import org.icij.datashare.asynctasks.TaskFactory;

public class WorkerUtils {
    public static final String DEFAULT_JAVA_QUEUE = "JAVA";

    public record RegisteredActivity(
        Class<? extends TemporalActivityImpl<?, ?, ?>> activityCls, String taskQueue, double weight
    ) {
    }

    public record RegisteredWorkflow(Class<? extends TemporalWorkflowImpl> workflowCls, String queue,
                                     List<RegisteredActivity> activities) {
    }

    public static final List<RegisteredWorkflow> REGISTERED_WORKFLOWS = List.of(
        new RegisteredWorkflow(ScanIndexNERWorkflowImpl.class, DEFAULT_JAVA_QUEUE, List.of(
            new RegisteredActivity(ScanActivityImpl.class, DEFAULT_JAVA_QUEUE, 1.0),
            new RegisteredActivity(IndexActivityImpl.class, DEFAULT_JAVA_QUEUE, 2.0),
            new RegisteredActivity(NERActivityImpl.class, DEFAULT_JAVA_QUEUE, 10)
        ))
    );

    public static List<Worker> createTemporalWorkers(
        TaskFactory taskFactory, WorkflowClient client, WorkerFactory workerFactory
    ) throws ReflectiveOperationException {
        HashMap<String, Worker> workers = new HashMap<>();
        REGISTERED_WORKFLOWS.forEach(rethrowConsumer(wf -> {
            String taskQueue = wf.queue;
            workers.computeIfAbsent(taskQueue, workerFactory::newWorker)
                .registerWorkflowImplementationTypes(wf.workflowCls);
            wf.activities.forEach(rethrowConsumer(act -> {
                workers.computeIfAbsent(act.taskQueue, workerFactory::newWorker)
                    .registerActivitiesImplementations(
                        createActivity(act.activityCls, taskFactory, client, act.weight));
            }));
        }));
        return workers.values().stream().toList();
    }

    private static <A extends TemporalActivityImpl<?, ?, ?>> A createActivity(
        Class<A> activityCls,
        TaskFactory taskFactory,
        WorkflowClient client,
        double progressWeight
    )
        throws NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
        return activityCls.getConstructor(TaskFactory.class, WorkflowClient.class, Double.class)
            .newInstance(taskFactory, client, progressWeight);
    }
}
