package org.icij.datashare.asynctasks.temporal;


import io.temporal.client.WorkflowClient;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.TaskFactory;
import org.icij.datashare.asynctasks.TaskRepository;
import org.icij.datashare.function.ThrowingSupplier;
import org.icij.datashare.tasks.RoutingStrategy;
import org.reflections.Reflections;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import static org.icij.datashare.asynctasks.TaskManagerTemporal.resolveWfTaskQueue;

/**
 * Which workflow implementations and activity instances should be served, and on which task queue. Knows nothing
 * about Temporal workers: feed it to {@link TemporalWorkers#start} to actually serve any of it.
 * <p>
 * Not thread safe: register everything (manually or through {@link #discoverWorkflows}) before starting workers.
 */
public class WorkflowRegistry {
    private static final String WORKFLOW_SUFFIX = "Workflow";
    private static final String WORKFLOW_METHOD_CLASS_NAME = WorkflowMethod.class.getName();

    private final Map<String, Set<Class<?>>> workflowsByQueue = new LinkedHashMap<>();
    private final Map<String, Set<Object>> activitiesByQueue = new LinkedHashMap<>();

    /**
     * Instantiates the activity implementation serving a workflow. Kept as a parameter of
     * {@link #discoverWorkflows} so that the reflective constructor contract of
     * {@link TemporalActivityImpl} stays in one place, and so that discovery can be exercised without building
     * real activities.
     */
    @FunctionalInterface
    public interface ActivityInstantiator {
        Object instantiate(Class<? extends TemporalActivityImpl<?, ?>> activityClass) throws Exception;
    }

    /**
     * Register a workflow implementation to be served on the given queue name.
     */
    public void registerWorkflow(Class<?> workflowClass, String queue) {
        Objects.requireNonNull(workflowClass, "workflowClass");
        Objects.requireNonNull(queue, "queue");
        workflowsByQueue.computeIfAbsent(queue, q -> new LinkedHashSet<>()).add(workflowClass);
    }

    /**
     * Register an activity instance to be served on the given queue name.
     */
    public void registerActivity(Object activity, String queue) {
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(queue, "queue");
        activitiesByQueue.computeIfAbsent(queue, q -> new LinkedHashSet<>()).add(activity);
    }

    /**
     * The queues that at least one registered workflow or activity is bound to. Callers that want a worker for
     * everything they registered can feed this straight into {@link TemporalWorkers#start}.
     */
    public Set<String> registeredQueues() {
        Set<String> queues = new LinkedHashSet<>(workflowsByQueue.keySet());
        queues.addAll(activitiesByQueue.keySet());
        return queues;
    }

    /**
     * The workflow implementations registered on a queue, in registration order, empty if the queue is unknown.
     */
    public Set<Class<?>> registeredWorkflows(String queue) {
        return Collections.unmodifiableSet(workflowsByQueue.getOrDefault(queue, Set.of()));
    }

    /**
     * The activity instances registered on a queue, in registration order, empty if the queue is unknown.
     */
    public Set<Object> registeredActivities(String queue) {
        return Collections.unmodifiableSet(activitiesByQueue.getOrDefault(queue, Set.of()));
    }

    /**
     * Discover the {@link WorkflowInterface} annotated interfaces of a package and register their implementation
     * along with the single activity implementation serving them, following this naming convention:
     * <ul>
     *     <li>discovered workflow interface: {@code XyzWorkflow}</li>
     *     <li>registered workflow implementation: {@code XyzWorkflowImpl}</li>
     *     <li>registered activity implementation: {@code XyzActivityImpl}</li>
     * </ul>
     * The queue each workflow is bound to is derived from {@code routingStrategy} and {@code group}; which of those
     * queues are actually served is decided later, when starting workers.
     *
     * @throws WorkflowRegistrationException if a discovered workflow does not follow the convention, so that a
     *      misnamed or missing implementation fails at startup rather than leaving workflows unserved forever.
     */
    public void discoverWorkflows(
            String packageName, ActivityInstantiator activityInstantiator,
            RoutingStrategy routingStrategy, Group group) {

        Reflections reflections = new Reflections(packageName);
        // We rely on naming convention rather than on inspection, that's OK as code is generated
        reflections.getTypesAnnotatedWith(WorkflowInterface.class)
                .stream()
                .filter(workflowInterfaceFilter())
                .forEach(c -> {
                    String taskQueue = resolveWfTaskQueue(routingStrategy, buildWorkflowKeyFrom(c), group);
                    registerWorkflow(workflowImplementation(c), taskQueue);
                    registerActivity(activityInstance(c, activityInstantiator), taskQueue);
                });
    }

    /**
     * Returns a Factory that can be used as the {@link ActivityInstantiator} required for instantiating activities
     * if they respect the convention of SingleActivityWorkflows of having a public constructor with the following parameters in this order :
     * {@link TaskFactory} taskFactory, {@link WorkflowClient} client, {@link TaskRepository} taskRepository, double ProgressWeight
     * @param activityCls
     * @param taskFactory
     * @param client
     * @param taskRepository
     * @param progressWeight
     * @return
     * @param <A>
     */
    public static <A extends TemporalActivityImpl<?, ?>> ThrowingSupplier<A> activityFactoryForSingleActivitiesWorkflow(
            Class<A> activityCls,
            TaskFactory taskFactory,
            WorkflowClient client,
            TaskRepository taskRepository,
            double progressWeight
    ) {
        return () -> activityCls
                .getConstructor(TaskFactory.class, WorkflowClient.class, TaskRepository.class, Double.class)
                .newInstance(taskFactory, client, taskRepository, progressWeight);
    }

    private static Class<?> workflowImplementation(Class<?> workflowInterface) {
        return loadClass(workflowInterface.getName() + "Impl", workflowInterface);
    }

    @SuppressWarnings("unchecked")
    private static Object activityInstance(Class<?> workflowInterface, ActivityInstantiator activityInstantiator) {
        String workflowClassName = workflowInterface.getName();
        if (!workflowClassName.endsWith(WORKFLOW_SUFFIX)) {
            throw new WorkflowRegistrationException(
                    "workflow interface " + workflowClassName + " does not end with " + WORKFLOW_SUFFIX
                            + ", cannot derive its activity implementation name");
        }
        String baseName = workflowClassName.substring(0, workflowClassName.length() - WORKFLOW_SUFFIX.length());
        Class<?> activityClass = loadClass(baseName + "ActivityImpl", workflowInterface);
        try {
            return activityInstantiator.instantiate((Class<? extends TemporalActivityImpl<?, ?>>) activityClass);
        } catch (Exception e) {
            throw new WorkflowRegistrationException(
                    "cannot instantiate activity " + activityClass.getName() + " for workflow " + workflowClassName, e);
        }
    }

    private static Class<?> loadClass(String className, Class<?> workflowInterface) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new WorkflowRegistrationException(
                    "cannot find " + className + " expected by the naming convention for workflow "
                            + workflowInterface.getName(), e);
        }
    }

    /**
     * Returns a predicate that filters all the interfaces that can be used as effective Workflow interfaces.
     * The filter will reject all the interfaces that don't have at least one WorkflowMethod
     */
    private static Predicate<Class<?>> workflowInterfaceFilter() {
        return c -> {
            if (!c.isInterface()) {
                return false;
            }
            // Skip signal/query-only interfaces (like TemporalWorkflow) that have no @WorkflowMethod
            return Arrays.stream(c.getDeclaredMethods()).anyMatch(WorkflowRegistry::isWorkflowMethod);
        };
    }

     static String buildWorkflowKeyFrom(Class<?> workflowInterface) {
        // We have to get method by name because of the dynamic class loader and proxies... inspection doesn't work
        // properly: m.isAnnotationPresent(WorkflowMethod.class) fails
        List<Method> annotated = Arrays.stream(workflowInterface.getDeclaredMethods())
                .filter(WorkflowRegistry::isWorkflowMethod)
                .toList();
        if (annotated.size() != 1) {
            throw new WorkflowRegistrationException("expected exactly one workflow method for " + workflowInterface);
        }
        return annotated.get(0).getAnnotation(WorkflowMethod.class).name();
    }

    private static boolean isWorkflowMethod(Method method) {
        return Arrays.stream(method.getAnnotations())
                .anyMatch(a -> a.annotationType().getName().equals(WORKFLOW_METHOD_CLASS_NAME));
    }

    public static class WorkflowRegistrationException extends RuntimeException {
        public WorkflowRegistrationException(String message) {
            super(message);
        }

        public WorkflowRegistrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
