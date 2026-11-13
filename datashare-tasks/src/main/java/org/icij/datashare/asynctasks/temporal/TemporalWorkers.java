package org.icij.datashare.asynctasks.temporal;

import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import java.io.Closeable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Starts the Temporal workers serving what a {@link WorkflowRegistry} holds.
 */
public class TemporalWorkers {
    private static final WorkflowImplementationOptions WF_IMPLEMENTATION_DEFAULT_OPTIONS =
            WorkflowImplementationOptions.newBuilder()
                                         .setFailWorkflowExceptionTypes(Error.class) // Unregistered workflows
                                         .build();
    // Workflow tasks are just orchestration decisions (dispatch to an activity, return its result), not the actual
    // work, so this stays modest per queue; the factory-wide thread pool below is sized to fit every worker's
    // share, so this cap is a real admission limit rather than one silently starved by shared-pool contention.
    private static final int MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION = 20;
    private final TemporalInterlocutor temporalInterlocutor;

    public TemporalWorkers(TemporalInterlocutor temporalInterlocutor) {
        this.temporalInterlocutor = temporalInterlocutor;
    }

    /**
     * Starts a worker for each of the given queues, serving the workflows and activities the registry holds for it.
     *
     * @param registry what to serve, keyed by task queue
     * @param listeningQueues the queues the workers will poll; registrations bound to any other queue are ignored
     * @param options the concurrency limits applied to every started worker
     * @return a handle closing the started worker factory
     */
    public Closeable start(WorkflowRegistry registry, Collection<String> listeningQueues,
                           TemporalWorkerOptions options) {
        Objects.requireNonNull(registry, "registry");

        Set<String> queues = new LinkedHashSet<>(listeningQueues);
        // one worker is created per queue below, and they all share this factory's workflow thread pool; size it
        // so every worker can actually reach its own MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION instead of the workers
        // silently starving each other under a fixed factory-wide default
        WorkerFactoryOptions workerFactoryOptions = WorkerFactoryOptions.newBuilder().setMaxWorkflowThreadCount(
                queues.size() * MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION).build();
        WorkerFactory workerFactory = temporalInterlocutor.newWorkerFactory(workerFactoryOptions);
        WorkerOptions workerOptions = WorkerOptions.newBuilder().setMaxConcurrentWorkflowTaskExecutionSize(
                MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION).setMaxConcurrentActivityExecutionSize(
                options.maxConcurrentActivitySize()).build();
        Map<String, Worker> workers = new HashMap<>();

        queues.forEach(queue -> {
            Set<Class<?>> workflowClasses = registry.registeredWorkflows(queue);
            if (!workflowClasses.isEmpty()) {
                workers.computeIfAbsent(queue, q -> workerFactory.newWorker(q, workerOptions))
                       .registerWorkflowImplementationTypes(WF_IMPLEMENTATION_DEFAULT_OPTIONS,
                                                            workflowClasses.toArray(Class<?>[]::new));
            }
            Set<Object> activities = registry.registeredActivities(queue);
            if (!activities.isEmpty()) {
                workers.computeIfAbsent(queue, q -> workerFactory.newWorker(q, workerOptions))
                       .registerActivitiesImplementations(activities.toArray());
            }
        });

        return new CloseableWorkerFactoryHandle(workerFactory, options.shutdownTimeout());
    }

    /**
     * Closing stops the polling, then waits for the running activities up to the shutdown timeout. The ones still
     * running after that are interrupted: Temporal then retries them on another worker.
     */
    private record CloseableWorkerFactoryHandle(WorkerFactory factory, Duration shutdownTimeout) implements Closeable {
        private static final Logger logger = LoggerFactory.getLogger(CloseableWorkerFactoryHandle.class);

        public CloseableWorkerFactoryHandle(WorkerFactory factory, Duration shutdownTimeout) {
            this.factory = factory;
            this.shutdownTimeout = shutdownTimeout;
            this.factory.start();
        }

        @Override
        public void close() throws IOException {
            synchronized (factory) {
                if (this.factory.isShutdown()) {
                    return;
                }
                logger.info("stopping temporal workers, waiting up to {} for running activities", shutdownTimeout);
                this.factory.shutdown();
                this.factory.awaitTermination(shutdownTimeout.toNanos(), TimeUnit.NANOSECONDS);
                if (!this.factory.isTerminated()) {
                    logger.warn("temporal workers still running after {}, interrupting them", shutdownTimeout);
                    this.factory.shutdownNow();
                }
            }
        }
    }
}
