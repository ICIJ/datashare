package org.icij.datashare.asynctasks.temporal;

import static io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED;
import static io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED;
import static io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED;
import static io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING;
import static io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED;
import static io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT;
import static org.icij.datashare.LambdaExceptionUtils.rethrowConsumer;
import static org.icij.datashare.LambdaExceptionUtils.rethrowFunction;
import static org.icij.datashare.asynctasks.TaskManagerTemporal.resolveWfTaskQueue;

import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.failure.ApplicationFailure;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskFactory;
import org.icij.datashare.function.ThrowingSupplier;
import org.icij.datashare.tasks.RoutingStrategy;
import org.reflections.Reflections;

public class TemporalHelper {
    public record RegisteredActivity(Supplier<?> activityFactory, String taskQueue) {
    }

    public record RegisteredWorkflow(Class<?> workflowCls, String taskQueue, List<RegisteredActivity> activities) {
    }

    private static WorkflowImplementationOptions WF_IMPLEMENTATION_DEFAULT_OPTIONS = WorkflowImplementationOptions.newBuilder()
            .setFailWorkflowExceptionTypes(Error.class) // Unregistered workflows
            .build();

    private static final String WORKFLOW_METHOD_CLASS_NAME = WorkflowMethod.class.getName();

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

    public static List<RegisteredWorkflow> discoverWorkflows(
        String packageName,
        TaskFactory taskFactory,
        WorkflowClient client,
        RoutingStrategy routingStrategy,
        Group workerGroup
    ) throws ClassNotFoundException {
        Reflections reflections = new Reflections(packageName);
        Predicate<Class<?>> workflowFilter = makeWorkflowFilter(routingStrategy, workerGroup);
        // We rely on naming convention rather than on inspection, that's OK as code is generated
        return reflections.getTypesAnnotatedWith(WorkflowInterface.class)
            .stream()
            .filter(workflowFilter)
            .map(rethrowFunction(c -> {
                String workflowKey = parseWorkflowKey(c);
                String workflowClassName = c.getName();
                String baseName = workflowClassName.replace("Workflow", "");
                Class<TemporalWorkflowImpl> wfImplClass = (Class<TemporalWorkflowImpl>) Class.forName(workflowClassName + "Impl");
                Class<TemporalActivityImpl<?, ?>> actImplCls = (Class<TemporalActivityImpl<?, ?>>) Class.forName(baseName + "ActivityImpl");
                String taskQueue = resolveWfTaskQueue(routingStrategy, workflowKey, workerGroup);
                List<RegisteredActivity> activities = List.of(new RegisteredActivity(activityFactory(actImplCls, taskFactory, client, 1d), taskQueue));
                return new RegisteredWorkflow(wfImplClass, taskQueue, activities);
            }))
            .toList();
    }

    public static WorkerFactory createTemporalWorkerFactory(List<RegisteredWorkflow> registeredWorkflows, WorkflowClient client) {
        return createTemporalWorkerFactory(registeredWorkflows, client, null);
    }

    public static WorkerFactory createTemporalWorkerFactory(
        List<RegisteredWorkflow> registeredWorkflows, WorkflowClient client, WorkerOptions workerOptions
    ) {
        WorkerFactory workerFactory = WorkerFactory.newInstance(client);
        HashMap<String, Worker> workers = new HashMap<>();
        registeredWorkflows.forEach(rethrowConsumer(wf -> {
            String wfTaskQueue = wf.taskQueue;
            workers.computeIfAbsent(wfTaskQueue, workerFactory::newWorker)
                .registerWorkflowImplementationTypes(WF_IMPLEMENTATION_DEFAULT_OPTIONS, wf.workflowCls);
            wf.activities.forEach(rethrowConsumer(act -> {
                workers.computeIfAbsent(act.taskQueue, q -> workerFactory.newWorker(q, workerOptions))
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

    public static <A extends TemporalActivityImpl<?, ?>> ThrowingSupplier<A> activityFactory(
        Class<A> activityCls,
        TaskFactory taskFactory,
        WorkflowClient client,
        double progressWeight
    ) {
        return () -> activityCls
            .getConstructor(TaskFactory.class, WorkflowClient.class, Double.class)
            .newInstance(taskFactory, client, progressWeight);
    }

    protected static <P, R> R taskWrapper(Function<P, R> taskFn, P payload, Set<Class<? extends Exception>> retriables) {
        try {
            return taskFn.apply(payload);
        } catch (Exception e) {
            if (retriables.stream().anyMatch(r -> r.isInstance(e))) {
                throw e;
            }
            throw ApplicationFailure.newNonRetryableFailureWithCause("Non retryable failure occurred", e.getMessage(),
                e);
        }
    }

    protected static <P, R> R taskWrapper(Function<P, R> taskFn, P payload) {
        return taskWrapper(taskFn, payload, Set.of());
    }

    protected static <R> R taskWrapper(Supplier<R> taskSupplier,
                                                            Set<Class<? extends Exception>> retriables) {
        return taskWrapper((t) -> taskSupplier.get(), null, retriables);
    }

    protected static <R> R taskWrapper(Supplier<R> taskSupplier) {
        return taskWrapper((t) -> taskSupplier.get(), null, Set.of());
    }

    private static String parseWorkflowKey(Class<?> workflowInterface) {
        // We have to get method by name because of the dynamic class loader and proxies... inspection doesn't work
        // properly: m.isAnnotationPresent(WorkflowMethod.class) fails
        List<Method> annotated = Arrays.stream(workflowInterface.getDeclaredMethods())
            .filter(m -> Arrays.stream(m.getAnnotations()).anyMatch(a -> a.annotationType().getName().equals(WORKFLOW_METHOD_CLASS_NAME)))
            .toList();
        if (annotated.size() != 1) {
            throw new RuntimeException("expected exactly one workflow method for " + workflowInterface);
        }
        return annotated.get(0).getAnnotation(WorkflowMethod.class).name();
    }

    private static Predicate<Class<?>> makeWorkflowFilter(RoutingStrategy routingStrategy, Group workerGroup) {
        return c -> {
            if (!c.isInterface()) {
                return false;
            }
            switch (routingStrategy) {
                case UNIQUE -> {
                    return true;
                }
                case GROUP, NAME -> {
                    return parseWorkflowKey(c).equals(asKey(workerGroup.getId()));
                }
                default -> throw new IllegalArgumentException("invalid routing strategy " + routingStrategy);
            }
        };
    }

    private static String asKey(String taskGroupId) {
        String asKey = taskGroupId.codePoints().mapToObj(c -> {
            String current = String.valueOf((char) c);
            return current.equals(current.toUpperCase()) ? "-" + current.toLowerCase() : current;
        }).collect(Collectors.joining());
        return asKey.startsWith("-") ? asKey.substring(1) : asKey;
    }

}