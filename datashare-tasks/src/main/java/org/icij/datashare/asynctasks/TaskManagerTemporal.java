package org.icij.datashare.asynctasks;

import io.grpc.StatusRuntimeException;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.common.SearchAttributes;
import org.icij.datashare.asynctasks.temporal.TemporalInterlocutor;
import org.icij.datashare.tasks.RoutingStrategy;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.icij.datashare.LambdaExceptionUtils.rethrowConsumer;
import static org.icij.datashare.asynctasks.Task.State.FINAL_STATES;
import static org.icij.datashare.asynctasks.temporal.TemporalInterlocutor.*;

public class TaskManagerTemporal implements TaskManager {

    private final TemporalInterlocutor temporal;
    private final RoutingStrategy routingStrategy;
    private final TaskRepository taskRepository;

    // TODO: add support for continue-as-new https://docs.temporal.io/develop/java/continue-as-new

    public static final String WORKFLOWS_DEFAULT = "default-java";

    public TaskManagerTemporal(TemporalInterlocutor temporal, TaskRepository taskRepository, RoutingStrategy routingStrategy) {
        this.temporal = temporal;
        this.routingStrategy = routingStrategy;
        this.taskRepository = taskRepository;
    }

    @Override
    public <V extends Serializable> String startTask(Task<V> taskView, Group group) throws TaskAlreadyExists {
        String taskId = taskView.id;
        try {
            temporal.createWorkflow(taskId, taskView.name, resolveWfTaskQueue(taskView.name ,group),
                    generateSearchAttributes(taskView), taskView.args);
        } catch (WorkflowExecutionAlreadyStarted ex) {
            throw new TaskAlreadyExists(taskId, ex);
        }
        return taskId;
    }

    @Override
    public <V extends Serializable> Task<V> getTask(String taskId) throws IOException, UnknownTask {
        return temporal.getTask(taskId);
    }

    @Override
    public Stream<Task<?>> getTasks(TaskFilters filters) throws IOException {
        Stream<Task<?>> tasks = temporal.getWorkflows(filters);
        // Temporal doesn't allow to search by args we have to post filter task retrieved with other filters
        if (filters.hasArgs()) {
            TaskFilters byArgs = TaskFilters.empty().withArgs(filters.getArgs());
            tasks = tasks.filter(byArgs::filter);
        }
        return tasks.sorted(Comparator.comparing(t -> t.createdAt));
    }

    @Override
    public Stream<String> getTaskIds(TaskFilters filters) throws IOException {
        return temporal.getWorkflowsIds(filters);
    }

    @Override
    public <V extends Serializable> Task<V> clearTask(String taskId) throws UnknownTask, IOException {
        Task<V> task = getTask(taskId);
        temporal.deleteExecution(taskId);
        return task;
    }

    @Override
    public boolean stopTask(String taskId) throws IOException, UnknownTask {
        return temporal.terminateWorkflow(taskId);
    }

    @Override
    public List<Task<?>> clearDoneTasks(TaskFilters filters) throws IOException {
        Set<Task.State> states = new HashSet<>(FINAL_STATES);
        if (filters.hasStates()) {
            states.retainAll(filters.getStates());
        }
        List<Task<?>> tasks = getTasks(filters.withStates(states)).toList();
        tasks.stream().map(Task::getId).forEach(rethrowConsumer(temporal::deleteExecution));
        return tasks;
    }

    @Override
    public boolean shutdown() throws IOException {
        // TODO: should we shutdown the client, is that even possible ?
        return true;
    }

    @Override
    public void clear() throws IOException {
        Set<String> taskIds = getTaskIds().collect(Collectors.toSet());
        taskIds.forEach(rethrowConsumer(temporal::deleteExecution));
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
            .set(MAX_PROGRESS_CUSTOM_ATTRIBUTE, 0d);
        Optional.ofNullable(taskView.getUser()).ifPresent(u -> {
            if(u.getId() != null) {
                builder.set(USER_CUSTOM_ATTRIBUTE, u.getId());
            }
        });
        return builder.build();
    }

    protected void awaitCleared(Set<String> taskIds, int timeout, TimeUnit timeUnit) throws IOException {
        long startTime = System.currentTimeMillis();
        long maxDuration = timeUnit.toMillis(timeout);
        while ((System.currentTimeMillis() - startTime < maxDuration)) {
            if (getTaskIds().noneMatch(taskIds::contains)) {
                return;
            }
            try {
                Thread.sleep(getTerminationPollingInterval());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("failed to clear task in " + timeout + " " + timeUnit);
    }

}