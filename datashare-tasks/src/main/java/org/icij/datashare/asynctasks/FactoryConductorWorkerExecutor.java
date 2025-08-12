package org.icij.datashare.asynctasks;

import com.netflix.conductor.client.http.TaskClient;
import com.netflix.conductor.sdk.workflow.executor.task.AnnotatedWorkerExecutor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FactoryConductorWorkerExecutor extends AnnotatedWorkerExecutor {
    // Custom worker executor which binds a TaskFactory to a Conductor tasks

    private static final Logger LOGGER = LoggerFactory.getLogger(FactoryConductorWorkerExecutor.class);
    private final int nWorkersPerTask;
    private final String routingKey;
    // This is ugly but needed since some attributes of annotated worker executor are private and it's the case
    // for workerConfiguration
    private final Integer pollingIntervalInMillis;
    private final Set<String> registeredName = new HashSet<>();
    private final TaskFactory taskFactory;
    public FactoryConductorWorkerExecutor(TaskFactory taskFactory, TaskClient taskClient, String routingKey) {
        this(taskFactory, taskClient, routingKey, 1, null);
    }

    public FactoryConductorWorkerExecutor(
        TaskFactory taskFactory, TaskClient taskClient, String routingKey, int pollingIntervalInMillis
    ) {
        this(taskFactory, taskClient, routingKey, 1, pollingIntervalInMillis);
    }

    public FactoryConductorWorkerExecutor(
        TaskFactory taskFactory, TaskClient taskClient, String routingKey, int nWorkersPerTask,
        Integer pollingIntervalInMillis
    ) {
        super(taskClient, pollingIntervalInMillis);
        // This is ugly but needed since some attributes of annotatedworker executor are private and it's the case
        // for workerConfiguration
        this.pollingIntervalInMillis = pollingIntervalInMillis;
        this.routingKey = routingKey;
        this.nWorkersPerTask = nWorkersPerTask;
        this.taskFactory = taskFactory;
    }

    public synchronized void initWorkers(String ignored) {
        this.scanTaskFactory(taskFactory);
        this.startPolling();
    }


    private void scanTaskFactory(TaskFactory factory) {
        long s = System.currentTimeMillis();
        Arrays.stream(factory.getClass().getDeclaredMethods())
            .forEach((classMeta) -> {
                List<Parameter> params = List.of(classMeta.getParameters());
                boolean isTaskMethod = params.size() >= 2
                    && params.get(0).getType().equals(Task.class) && params.get(1).getType().equals(Function.class);
                if (isTaskMethod) {
                    addMeta(factory, classMeta);
                }
            });
        LOGGER.info("Took {} ms to scan all the classes, loading {} tasks", System.currentTimeMillis() - s,
            this.workers.size());
    }

    public void addMeta(TaskFactory factory, Method method) {
        Class<?> taskClass = method.getReturnType();
        Optional.ofNullable(taskClass.getAnnotation(ConductorTask.class))
            .ifPresent(a -> this.addTaskMethod(factory, a, method));
    }

    private void addTaskMethod(TaskFactory factory, ConductorTask annotation, Method method) {
        String name = annotation.name();
        if (registeredName.contains(name)) {
            String msg = "task with name " + name + " has been registered at least twice, make sure task "
                + ConductorTask.class.getSimpleName()
                + " decorators use unique names";
            throw new RuntimeException(msg);
        }
        registeredName.add(name);
        // TODO: here we need to apply some filtering logic to avoid adding a new thread/worker if we know we won't
        //  poll from it...
        Optional.ofNullable(routingKey).ifPresent(r -> this.workerDomains.put(name, r));
        this.workerToThreadCount.put(name, nWorkersPerTask);
        int pollingInterval = Optional.ofNullable(this.pollingIntervalInMillis).orElse(100);
        this.workerToPollingInterval.put(name, pollingInterval);
        FactoryConductorWorker executor = new FactoryConductorWorker(this.taskClient, factory, name, method);
        executor.setPollingInterval(this.workerToPollingInterval.get(name));
        this.workers.add(executor);
        LOGGER.info("Adding worker for task {}, method {} with {} thread per task and polling interval set to {} ms",
            name, method, nWorkersPerTask, pollingInterval);
    }
}
