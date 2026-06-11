package org.icij.datashare.asynctasks;

import io.grpc.StatusRuntimeException;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.common.SearchAttributes;
import org.icij.datashare.asynctasks.temporal.TemporalInterlocutor;
import org.icij.datashare.tasks.RoutingStrategy;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.icij.datashare.LambdaExceptionUtils.rethrowConsumer;
import static org.icij.datashare.asynctasks.Task.State.FINAL_STATES;
import static org.icij.datashare.asynctasks.temporal.TemporalInterlocutor.*;

public class TaskManagerTemporal implements TaskManager {

    private final TemporalInterlocutor temporal;
    private final RoutingStrategy routingStrategy;
    private final TaskRepository taskRepository;
    protected final ConcurrentHashMap<String, CompletableFuture<Serializable>> pendingListeners = new ConcurrentHashMap<>();

    // TODO: add support for continue-as-new https://docs.temporal.io/develop/java/continue-as-new

    public static final String WORKFLOWS_DEFAULT = "default-java";

    public TaskManagerTemporal(TemporalInterlocutor temporal, TaskRepository taskRepository, RoutingStrategy routingStrategy) {
        this.temporal = temporal;
        this.routingStrategy = routingStrategy;
        this.taskRepository = taskRepository;
    }

    @Override
    public <V extends Serializable> String startTask(Task<V> taskView, Group group) throws IOException, TaskAlreadyExists {
        String taskId = taskView.id;
        try {
            taskRepository.insert(taskView, group);
            temporal.createWorkflow(taskId, taskView.name, resolveWfTaskQueue(taskView.name ,group),
                    generateSearchAttributes(taskView), taskView.args);
            taskView.setState(Task.State.RUNNING);
            taskRepository.update(taskView);
            attachCompletionListener(taskId);
        } catch (WorkflowExecutionAlreadyStarted ex) {
            throw new TaskAlreadyExists(taskId, ex);
        }
        return taskId;
    }

    @Override
    public <V extends Serializable> Task<V> getTask(String taskId) throws IOException, UnknownTask {
        return taskRepository.getTask(taskId);
    }

    @Override
    public Stream<Task<?>> getTasks(TaskFilters filters) throws IOException {
        return taskRepository.getTasks(filters);
    }

    @Override
    public Stream<String> getTaskIds(TaskFilters filters) throws IOException {
        return taskRepository.getTaskIds(filters);
    }

    @Override
    public <V extends Serializable> Task<V> clearTask(String taskId) throws UnknownTask, IOException {
        if (this.getTask(taskId).getState() == Task.State.RUNNING) {
            throw new IllegalStateException(String.format("task id <%s> is in RUNNING state", taskId));
        }
        logger.info("deleting task id <{}>", taskId);
        temporal.deleteExecution(taskId);
        return taskRepository.delete(taskId);
    }

    @Override
    public boolean stopTask(String taskId) throws IOException, UnknownTask {
        //TODO Check if synchronous call
        if(temporal.terminateWorkflow(taskId)) {
            Task<?> task = taskRepository.getTask(taskId);
            task.setState(Task.State.CANCELLED);
            taskRepository.update(task);
            return true;
        }
        return false;
    }

    @Override
    public List<Task<?>> clearDoneTasks(TaskFilters filters) throws IOException {
        Set<Task.State> states = new HashSet<>(FINAL_STATES);
        if (filters.hasStates()) {
            states.retainAll(filters.getStates());
        }
        List<Task<?>> tasks = getTasks(filters.withStates(states)).toList();
        tasks.stream().map(Task::getId).forEach(rethrowConsumer(id -> {
            temporal.deleteExecution(id);
            taskRepository.delete(id);
        }));
        return tasks;
    }

    /**
     * Retrieves running task in DB to get their status in Temporal, and attach completion listener if needed
     * @throws IOException
     */
    public void reconcileTasks() throws IOException {
        logger.info("Scanning repository for tasks to reconcile");
        taskRepository.getTasks(TaskFilters.empty().withStates(Set.of(Task.State.RUNNING)))
            .forEach(repoTask -> {
                logger.info("Reconciling task {} with Temporal", repoTask.getId());
                try {
                    Task<Serializable> temporalTask = temporal.getTask(repoTask.id);
                    if (temporalTask.isFinished()) {
                        logger.info("Task {} is finished in Temporal, update the completion information in repository", repoTask.getId());
                        taskRepository.update(temporalTask);
                    } else {
                        logger.info("Task {} is still running in Temporal, attaching completion listener", repoTask.getId());
                        attachCompletionListener(repoTask.id);
                    }
                } catch (UnknownTask e) {
                    logger.warn("Task {} is RUNNING in repository but not found in Temporal. The task will never complete", repoTask.id);
                } catch (IOException e) {
                    logger.warn("Failed to reconcile task {}", repoTask.id, e);
                }
            });
    }

    /**
     * Add a listener to execute upon completion of a Task.
     * Can be used upon creation of a Task, or at startup for reconciliation
     * @param taskId
     */
    private void attachCompletionListener(String taskId) {
        CompletableFuture<Serializable> future = temporal.createWorkflowStub(taskId)
            .getResultAsync(Serializable.class);
        pendingListeners.put(taskId, future);
        future.whenComplete((result, ex) -> {
            pendingListeners.remove(taskId);
            if (ex instanceof CancellationException) {
                return;
            }
            try {
                Task<Serializable> storedTask = taskRepository.getTask(taskId);
                if (storedTask.isFinished()) {
                    //Do nothing
                    return;
                }
                updateTaskWithCompletionInfoFromTemporal(storedTask);
                taskRepository.update(storedTask);
            } catch (IOException | UnknownTask e) {
                logger.warn("failed to update task {} on completion", taskId, e);
            }
        });
    }


    /**
     * Updates the task in parameter {@param inRepository} with state, result and error from Temporal
     * @param inRepository
     */
    private <T extends Serializable> void updateTaskWithCompletionInfoFromTemporal(Task<T> inRepository) {
        Task<T> inTemporal = temporal.getTask(inRepository.getId());
        if(inTemporal == null) {
            logger.warn("Unable to retrieve task {} from Temporal. Cannot update completion infos in repository", inRepository.getId());
            return;
        }
        inRepository.setState(inTemporal.getState());

        if(inTemporal.getResult() != null) {
            inRepository.setResult(inTemporal.getResult());
        }

        if(inTemporal.getError() != null) {
            inRepository.setError(inTemporal.getError());
        }

    }



    @Override
    public boolean shutdown() throws IOException {
        // TODO: should we shutdown the client, is that even possible ?
        return true;
    }

    @Override
    public void clear() throws IOException {
        // We need to cancel the Future first, or else it's complete lambda function will be called by Temporal,
        // reintroducing it in the taskRepository.
        new HashMap<>(pendingListeners).values().forEach(f -> f.cancel(false));
        Set<String> taskIds = getTaskIds().collect(Collectors.toSet());
        taskIds.forEach(rethrowConsumer(id -> {
            temporal.deleteExecution(id);
            taskRepository.delete(id);
        }));
    }

    @Override
    public boolean getHealth() throws IOException {
        try {
            return temporal.getHealth();
        } catch (StatusRuntimeException ignored) {
            return false;
        }
    }

    @Override
    public int getTerminationPollingInterval() {
        // We need a high interval to let the server propagate deletions
        return 1000;
    }


    @Override
    public void close() throws IOException {
    }

    public static String resolveWfTaskQueue(RoutingStrategy routingStrategy, String queueKey, Group group) {
        switch (routingStrategy) {
            case UNIQUE -> {
                return WORKFLOWS_DEFAULT;
            }
            case GROUP -> {
                return group.getId().toLowerCase();
            }
            case NAME -> {
                return queueKey.toLowerCase();
            }
            default -> throw new IllegalArgumentException("invalid routing strategy " + routingStrategy);
        }
    }

    protected String resolveWfTaskQueue(String taskName, Group group) {
        return resolveWfTaskQueue(routingStrategy, taskName, group);
    }

    static SearchAttributes generateSearchAttributes(Task<?> taskView) {
        SearchAttributes.Builder builder = SearchAttributes.newBuilder()
            .set(PROGRESS_CUSTOM_ATTRIBUTE, 0d)
            .set(MAX_PROGRESS_CUSTOM_ATTRIBUTE, 1d);
        Optional.ofNullable(taskView.getUser()).ifPresent(u -> {
            if(u.getId() != null) {
                builder.set(USER_CUSTOM_ATTRIBUTE, u.getId());
            }
        });
        return builder.build();
    }
}