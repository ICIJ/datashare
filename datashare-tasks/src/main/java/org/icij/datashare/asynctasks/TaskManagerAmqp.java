package org.icij.datashare.asynctasks;

import java.util.function.Consumer;
import org.icij.datashare.asynctasks.bus.amqp.*;

import org.icij.datashare.tasks.RoutingStrategy;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

public class TaskManagerAmqp implements TaskManager {
    private final TaskRepository tasks;
    private final RoutingStrategy routingStrategy;
    private final AmqpInterlocutor amqp;
    private final AmqpConsumer<TaskEvent, Consumer<TaskEvent>> eventConsumer;

    public TaskManagerAmqp(AmqpInterlocutor amqp, TaskRepository taskRepository) throws IOException {
        this(amqp, taskRepository, RoutingStrategy.UNIQUE);
    }

    public TaskManagerAmqp(AmqpInterlocutor amqp, TaskRepository tasks, RoutingStrategy routingStrategy) throws IOException {
        this(amqp, tasks, routingStrategy, null);
    }

    public TaskManagerAmqp(AmqpInterlocutor amqp, TaskRepository tasks, RoutingStrategy routingStrategy, Runnable eventCallback) throws IOException {
        this.amqp = amqp;
        this.tasks = tasks;
        this.routingStrategy = routingStrategy;
        eventConsumer = new AmqpConsumer<>(amqp, event ->
                ofNullable(TaskManager.super.handleAck(event)).flatMap(t ->
                        ofNullable(eventCallback)).ifPresent(Runnable::run), AmqpQueue.MANAGER_EVENT, TaskEvent.class).consumeEvents();
    }

    @Override
    public boolean stopTask(String taskId) throws IOException, UnknownTask {
        Task<?> taskView = this.getTask(taskId);
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
    public <V extends Serializable> Task<V> clearTask(String taskId) throws IOException, UnknownTask {
        if (this.getTask(taskId).getState() == Task.State.RUNNING) {
            throw new IllegalStateException(String.format("task id <%s> is already in RUNNING state", taskId));
        }
        logger.info("deleting task id <{}>", taskId);
        return (Task<V>) tasks.remove(taskId).task();
    }

    @Override
    public boolean shutdown() throws IOException {
        amqp.publish(AmqpQueue.WORKER_EVENT, new ShutdownEvent());
        return true;
    }

    @Override
    public <V extends Serializable> void insert(Task<V> task, Group group) throws IOException, TaskAlreadyExists {
        tasks.insert(task, group);
    }

    @Override
    public <V extends Serializable> void update(Task<V> task) throws IOException, UnknownTask {
        tasks.update(task);
    }

    @Override
    public <V extends Serializable> void enqueue(Task<V> task) throws IOException {
        switch (routingStrategy) {
            case GROUP -> amqp.publish(AmqpQueue.TASK, this.tasks.get(task.id).group().id().name(), task);
            case NAME -> amqp.publish(AmqpQueue.TASK, task.name, task);
            default -> amqp.publish(AmqpQueue.TASK, task);
        }
    }

    @Override
    public <V extends Serializable> Task<V> getTask(String taskId) throws IOException, UnknownTask {
        return tasks.getTask(taskId);
    }

    @Override
    public List<Task<?>> getTasks() {
        return tasks.values().stream().map(TaskMetadata::task).collect(toList());
    }

    @Override
    public Group getTaskGroup(String taskId) {
        return tasks.get(taskId).group();
    }

    @Override
    public List<Task<?>> clearDoneTasks() {
        return tasks.values().stream().map(TaskMetadata::task).filter(Task::isFinished)
            .map(t -> tasks.remove(t.id).task()).collect(toList());
    }

    public void close() throws IOException {
        clearDoneTasks();
        eventConsumer.shutdown();
    }

    @Override
    public void clear() {
        tasks.clear();
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
