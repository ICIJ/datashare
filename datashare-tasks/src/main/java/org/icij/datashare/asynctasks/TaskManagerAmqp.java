package org.icij.datashare.asynctasks;

import com.rabbitmq.client.ShutdownSignalException;
import org.icij.datashare.asynctasks.bus.amqp.*;

import org.icij.datashare.tasks.RoutingStrategy;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
    public boolean stopTask(String taskId) {
        Task<?> taskView = tasks.get(taskId);
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
    public <V> Task<V> clearTask(String taskId) {
        if (tasks.get(taskId).getState() == Task.State.RUNNING) {
            throw new IllegalStateException(String.format("task id <%s> is already in RUNNING state", taskId));
        }
        logger.info("deleting task id <{}>", taskId);
        return (Task<V>) tasks.remove(taskId);
    }

    @Override
    public boolean shutdown() throws IOException {
        amqp.publish(AmqpQueue.WORKER_EVENT, new ShutdownEvent());
        return true;
    }

    public <V> boolean save(Task<V> task) {
        Task<?> oldVal = tasks.put(task.id, task);
        return oldVal == null;
    }

    @Override
    public <V> void enqueue(Task<V> task) throws IOException {
        switch (routingStrategy) {
            case GROUP -> amqp.publish(AmqpQueue.TASK, task.getGroup().id(), task);
            case NAME -> amqp.publish(AmqpQueue.TASK, task.name, task);
            default -> amqp.publish(AmqpQueue.TASK, task);
        }
    }

    @Override
    public <V> Task<V> getTask(String taskId) {
        return (Task<V>) tasks.get(taskId);
    }

    @Override
    public List<Task<?>> getTasks() {
        return new LinkedList<>(tasks.values());
    }

    @Override
    public List<Task<?>> clearDoneTasks() {
        return tasks.values().stream().filter(f -> f.getState() != Task.State.RUNNING).map(t -> tasks.remove(t.id)).collect(toList());
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
    public boolean getHealth() throws IOException {
        try {
            logger.info("sending monitoring event");
            amqp.publish(AmqpQueue.MONITORING, new MonitoringEvent());
        } catch (ShutdownSignalException e) {
            logger.error("error sending monitoring event", e);
            return false;
        }
        return true;
    }
}
