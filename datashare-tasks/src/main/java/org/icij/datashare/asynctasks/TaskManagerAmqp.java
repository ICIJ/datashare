package org.icij.datashare.asynctasks;

import java.util.List;
import java.util.function.Consumer;
import org.icij.datashare.asynctasks.bus.amqp.*;

import org.icij.datashare.tasks.RoutingStrategy;

import java.io.IOException;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.asynctasks.Task.State.FINAL_STATES;

public class TaskManagerAmqp implements TaskManager {
    protected static final int DEFAULT_TASK_POLLING_INTERVAL_MS = 5000;
    private final TaskRepository tasks;
    private final RoutingStrategy routingStrategy;
    private final AmqpInterlocutor amqp;
    private final AmqpConsumer<TaskEvent, Consumer<TaskEvent>> eventConsumer;
    private final int taskPollingIntervalMs;

    public TaskManagerAmqp(AmqpInterlocutor amqp, TaskRepository taskRepository) throws IOException {
        this(amqp, taskRepository, RoutingStrategy.UNIQUE);
    }

    public TaskManagerAmqp(AmqpInterlocutor amqp, TaskRepository tasks, RoutingStrategy routingStrategy) throws IOException {
        this(amqp, tasks, routingStrategy, null);
    }

    public TaskManagerAmqp(AmqpInterlocutor amqp, TaskRepository tasks, RoutingStrategy routingStrategy, Runnable eventCallback) throws IOException {
        this(amqp, tasks, routingStrategy, eventCallback, DEFAULT_TASK_POLLING_INTERVAL_MS);
    }

    public TaskManagerAmqp(AmqpInterlocutor amqp, TaskRepository tasks, RoutingStrategy routingStrategy, Runnable eventCallback, int taskPollingIntervalMs) throws IOException {
        this.amqp = amqp;
        this.tasks = tasks;
        this.routingStrategy = routingStrategy;
        this.taskPollingIntervalMs = taskPollingIntervalMs;
        eventConsumer = new AmqpConsumer<>(amqp, event ->
                ofNullable(TaskManager.super.handleAck(event)).flatMap(t ->
                        ofNullable(eventCallback)).ifPresent(Runnable::run), AmqpQueue.MANAGER_EVENT, TaskEvent.class).consumeEvents();
    }

    @Override
    public boolean stopTask(String taskId) throws IOException, UnknownTask {
        Task taskView = this.getTask(taskId);
        if (taskView != null) {
            try {
                logger.info("sending cancel event for {}", taskId);
                amqp.publish(AmqpQueue.WORKER_EVENT, new CancelEvent(taskId, false));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return true;
        } else {
            logger.warn("unknown task id <{}> for cancel call", taskId);
            return false;
        }
    }

    @Override
    public Task clearTask(String taskId) throws IOException, UnknownTask {
        if (this.getTask(taskId).getState() == Task.State.RUNNING) {
            throw new IllegalStateException(String.format("task id <%s> is already in RUNNING state", taskId));
        }
        logger.info("deleting task id <{}>", taskId);
        return tasks.delete(taskId);
    }

    @Override
    public boolean shutdown() throws IOException {
        amqp.publish(AmqpQueue.WORKER_EVENT, new ShutdownEvent());
        return true;
    }

    @Override
    public void insert(Task task, Group group) throws IOException, TaskAlreadyExists {
        tasks.insert(task, group);
    }

    @Override
    public void update(Task task) throws IOException, UnknownTask {
        tasks.update(task);
    }

    @Override
    public void enqueue(Task task) throws IOException {
        switch (routingStrategy) {
            case GROUP -> amqp.publish(AmqpQueue.TASK, this.tasks.getTaskGroup(task.id).id().name(), task);
            case NAME -> amqp.publish(AmqpQueue.TASK, task.name, task);
            default -> amqp.publish(AmqpQueue.TASK, task);
        }
    }

    @Override
    public Task getTask(String taskId) throws IOException, UnknownTask {
        return tasks.getTask(taskId);
    }

    @Override
    public Stream<Task> getTasks(TaskFilters filters) throws IOException {
        return tasks.getTasks(filters);
    }

    @Override
    public Group getTaskGroup(String taskId) throws IOException {
        return tasks.getTaskGroup(taskId);
    }

    @Override
    public List<Task> clearDoneTasks(TaskFilters filters) throws IOException {
        // Require tasks to be in final state and apply user filters
        Stream<Task> taskStream = tasks.getTasks(filters.withStates(FINAL_STATES))
            .map(t -> {
                try {
                    return tasks.delete(t.id);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        return taskStream.toList();
    }

    @Override
    public int getTerminationPollingInterval() {
        return taskPollingIntervalMs;
    }

    public void close() throws IOException {
        clearDoneTasks();
        eventConsumer.shutdown();
    }

    @Override
    public void clear() throws IOException {
        tasks.deleteAll();
    }

    @Override
    public boolean getHealth() {
        try {
            if (amqp.hasMonitoringQueue()) {
                logger.info("sending monitoring event");
                amqp.publish(AmqpQueue.MONITORING, new MonitoringEvent());
                return true;
            } else {
                return amqp.isConnectionOpen();
            }
        } catch (RuntimeException|IOException e) {
            logger.error("error sending monitoring event", e);
            return false;
        }
    }
}
