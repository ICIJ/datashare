package org.icij.datashare.asynctasks.temporal;

import static io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED;
import static io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED;
import static io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED;
import static io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING;
import static io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED;
import static io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT;
import static org.icij.datashare.LambdaExceptionUtils.rethrowConsumer;

import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.failure.ApplicationFailure;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkflowImplementationOptions;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.icij.datashare.asynctasks.Task;

public class TemporalHelper {
    public record RegisteredActivity(Supplier<Object> activityFactory, String taskQueue) {}
    public record RegisteredWorkflow(Class<?> workflowCls, String queue, List<RegisteredActivity> activities) {}
    private static WorkflowImplementationOptions workflowImplementationOptions = WorkflowImplementationOptions
        .newBuilder()
        .setFailWorkflowExceptionTypes(Error.class) // Unregistered workflows
        .build();

    public static class CloseableWorkerFactoryHandle implements AutoCloseable {
        private final WorkerFactory factory;

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

        public WorkerFactory getFactory() {
            return factory;
        }
    }

    public static WorkerFactory createTemporalWorkerFactory(
        List<RegisteredWorkflow> registeredWorkflows, WorkerFactory workerFactory
    ) {
        HashMap<String, Worker> workers = new HashMap<>();
        registeredWorkflows.forEach(rethrowConsumer(wf -> {
            String taskQueue = wf.queue;
            workers.computeIfAbsent(taskQueue, workerFactory::newWorker)
                .registerWorkflowImplementationTypes(workflowImplementationOptions, wf.workflowCls);
            wf.activities.forEach(rethrowConsumer(act -> {
                workers.computeIfAbsent(act.taskQueue, workerFactory::newWorker)
                    .registerActivitiesImplementations(act.activityFactory.get());
            }));
        }));
        return workerFactory;
    }

    public static Task.State asTaskState(WorkflowExecutionStatus status) {
        return switch (status) {
            case WORKFLOW_EXECUTION_STATUS_UNSPECIFIED ->
                throw new RuntimeException("unknown temporal workflow state " + status);
            // Could be queued but temporal as no such state...
            case WORKFLOW_EXECUTION_STATUS_RUNNING -> Task.State.RUNNING;
            case WORKFLOW_EXECUTION_STATUS_COMPLETED -> Task.State.DONE;
            case WORKFLOW_EXECUTION_STATUS_FAILED -> Task.State.ERROR;
            case WORKFLOW_EXECUTION_STATUS_CANCELED -> Task.State.CANCELLED;
            case WORKFLOW_EXECUTION_STATUS_TERMINATED -> Task.State.CANCELLED;
            case WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW ->
                throw new RuntimeException("continue as new is not currently supported " + status);
            case WORKFLOW_EXECUTION_STATUS_TIMED_OUT -> Task.State.ERROR;
            case UNRECOGNIZED -> throw new RuntimeException("unknown temporal workflow state " + status);
        };
    }

    public static Stream<WorkflowExecutionStatus> asWorkflowExecutionStatus(Task.State state) {
        return switch (state) {
            case CREATED -> Stream.of(WORKFLOW_EXECUTION_STATUS_CANCELED, WORKFLOW_EXECUTION_STATUS_TERMINATED);
            // This is merged with running in Temporal
            case QUEUED -> Stream.of(WORKFLOW_EXECUTION_STATUS_RUNNING);
            case RUNNING -> Stream.of(WORKFLOW_EXECUTION_STATUS_RUNNING);
            case CANCELLED -> Stream.of(WORKFLOW_EXECUTION_STATUS_CANCELED, WORKFLOW_EXECUTION_STATUS_TERMINATED);
            case ERROR -> Stream.of(WORKFLOW_EXECUTION_STATUS_FAILED, WORKFLOW_EXECUTION_STATUS_TIMED_OUT);
            case DONE -> Stream.of(WORKFLOW_EXECUTION_STATUS_COMPLETED);
        };
    }

    protected static <P, R> R taskWrapper(Function<P, R> taskFn, P payload, Set<Class<? extends Exception>> retriables) {
        try {
            return taskFn.apply(payload);
        } catch (Exception e) {
            if (retriables.stream().anyMatch(r -> r.isInstance(e))) {
                throw e;
            }
            throw ApplicationFailure.newNonRetryableFailureWithCause("Non retryable failure occurred", e.getMessage(), e);
        }
    }

    protected static <P, R> R taskWrapper(Function<P, R> taskFn, P payload) {
        return taskWrapper(taskFn, payload, Set.of());
    }

    protected static <R> R taskWrapper(Supplier<R> taskSupplier, Set<Class<? extends Exception>> retriables) {
        return taskWrapper((t) -> taskSupplier.get(), null, retriables);
    }

    protected static <R> R taskWrapper(Supplier<R> taskSupplier) {
        return taskWrapper((t) -> taskSupplier.get(), null, Set.of());
    }

}