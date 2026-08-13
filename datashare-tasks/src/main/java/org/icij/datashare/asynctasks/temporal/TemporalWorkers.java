package org.icij.datashare.asynctasks.temporal;


import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Starts the Temporal workers serving what a {@link WorkflowRegistry} holds.
 */
public class TemporalWorkers {
    private static final WorkflowImplementationOptions WF_IMPLEMENTATION_DEFAULT_OPTIONS = WorkflowImplementationOptions.newBuilder()
            .setFailWorkflowExceptionTypes(Error.class) // Unregistered workflows
            .build();

    private TemporalWorkers() {
    }

    /**
     * Starts a worker for each of the given queues, serving the workflows and activities the registry holds for it.
     *
     * @param client the client the workers poll Temporal with
     * @param registry what to serve, keyed by task queue
     * @param listeningQueues the queues the workers will poll; registrations bound to any other queue are ignored
     * @param options the concurrency limits applied to every started worker
     * @return a handle closing the started worker factory
     */
    public static Closeable start(
            WorkflowClient client, WorkflowRegistry registry,
            Collection<String> listeningQueues, TemporalWorkerOptions options) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(registry, "registry");

        WorkerFactory workerFactory = WorkerFactory.newInstance(client);
        WorkerOptions workerOptions = WorkerOptions.newBuilder()
                .setMaxConcurrentWorkflowTaskExecutionSize(options.maxConcurrentWorkflowSize())
                .setMaxConcurrentActivityExecutionSize(options.maxConcurrentActivitySize())
                .build();
        Map<String, Worker> workers = new HashMap<>();

        // deduplicated: registering the same type twice on a worker is a TypeAlreadyRegisteredException
        new LinkedHashSet<>(listeningQueues).forEach(queue -> {
            Set<Class<?>> workflowClasses = registry.registeredWorkflows(queue);
            if (!workflowClasses.isEmpty()) {
                workers.computeIfAbsent(queue, q -> workerFactory.newWorker(q, workerOptions))
                        .registerWorkflowImplementationTypes(WF_IMPLEMENTATION_DEFAULT_OPTIONS, workflowClasses.toArray(Class<?>[]::new));
            }
            Set<Object> activities = registry.registeredActivities(queue);
            if (!activities.isEmpty()) {
                workers.computeIfAbsent(queue, q -> workerFactory.newWorker(q, workerOptions))
                        .registerActivitiesImplementations(activities.toArray());
            }
        });

        return new CloseableWorkerFactoryHandle(workerFactory);
    }

    private record CloseableWorkerFactoryHandle(WorkerFactory factory) implements Closeable {
        public CloseableWorkerFactoryHandle(WorkerFactory factory) {
            this.factory = factory;
            this.factory.start();
        }

        @Override
        public void close() throws IOException {
            synchronized (factory) {
                if (!this.factory.isShutdown()) {
                    this.factory.shutdown();
                }
            }
        }
    }
}
