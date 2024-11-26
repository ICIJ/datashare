package org.icij.datashare.asynctasks;

import java.util.Optional;
import org.icij.datashare.asynctasks.bus.amqp.AmqpConsumer;
import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.asynctasks.bus.amqp.AmqpQueue;
import org.icij.datashare.asynctasks.bus.amqp.CancelEvent;
import org.icij.datashare.asynctasks.bus.amqp.ShutdownEvent;
import org.icij.datashare.asynctasks.bus.amqp.TaskEvent;

import org.icij.datashare.tasks.RoutingStrategy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

public class TaskManagerAmqp implements TaskManager {
    private final Map<String, TaskMetadata<?>> taskMetas;
    private final RoutingStrategy routingStrategy;
    private final AmqpInterlocutor amqp;
    private final AmqpConsumer<TaskEvent, Consumer<TaskEvent>> eventConsumer;

    public TaskManagerAmqp(AmqpInterlocutor amqp, Map<String, TaskMetadata<?>> taskMetas) throws IOException {
        this(amqp, taskMetas, RoutingStrategy.UNIQUE);
    }

    public TaskManagerAmqp(AmqpInterlocutor amqp, Map<String, TaskMetadata<?>> taskMetas, RoutingStrategy routingStrategy) throws IOException {
        this(amqp, taskMetas, routingStrategy, null);
    }

    public TaskManagerAmqp(AmqpInterlocutor amqp, Map<String, TaskMetadata<?>> taskMetas, RoutingStrategy routingStrategy, Runnable eventCallback) throws IOException {
        this.amqp = amqp;
        this.taskMetas = taskMetas;
        this.routingStrategy = routingStrategy;
        eventConsumer = new AmqpConsumer<>(amqp, event ->
                ofNullable(TaskManager.super.handleAck(event)).flatMap(t ->
                        ofNullable(eventCallback)).ifPresent(Runnable::run), AmqpQueue.MANAGER_EVENT, TaskEvent.class).consumeEvents();
    }

    @Override
    public boolean stopTask(String taskId) {
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
    public <V> Task<V> clearTask(String taskId) {
        if (this.getTask(taskId).getState() == Task.State.RUNNING) {
            throw new IllegalStateException(String.format("task id <%s> is already in RUNNING state", taskId));
        }
        logger.info("deleting task id <{}>", taskId);
        return (Task<V>) taskMetas.remove(taskId).task();
    }

    @Override
    public boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException, IOException {
        amqp.publish(AmqpQueue.WORKER_EVENT, new ShutdownEvent());
        return true;
    }

    @Override
    public <V> void saveMetadata(TaskMetadata<V> taskMetadata) throws TaskAlreadyExists {
        String taskId = taskMetadata.taskId();
        if (taskMetas.containsKey(taskId)) {
            throw new TaskAlreadyExists(taskId);
        }
        this.taskMetas.put(taskId, taskMetadata);
    }

    @Override
    public <V> void persistUpdate(Task<V> task) throws UnknownTask {
        TaskMetadata<V> updated = (TaskMetadata<V>) taskMetas.get(task.id);
        if (updated == null) {
            throw new UnknownTask(task.id);
        }
        updated = updated.withTask(task);
        this.taskMetas.put(task.id, updated);
    }

    @Override
    public void enqueue(Task<?> task) throws IOException {
        switch (routingStrategy) {
            case GROUP -> amqp.publish(AmqpQueue.TASK, this.taskMetas.get(task.id).group().id(), task);
            case NAME -> amqp.publish(AmqpQueue.TASK, task.name, task);
            default -> amqp.publish(AmqpQueue.TASK, task);
        }
    }

    @Override
    public <V> Task<V> getTask(String taskId) {
        return (Task<V>) Optional.ofNullable(taskMetas.get(taskId)).map(TaskMetadata::task).orElse(null);
    }

    @Override
    public List<Task<?>> getTasks() {
        return taskMetas.values().stream().map(TaskMetadata::task).collect(toList());
    }

    @Override
    public List<Task<?>> getTasks(Pattern pattern) throws IOException {
        return this.getTasks(taskMetas.values().stream().map(TaskMetadata::task), pattern);
    }

    @Override
    public Group getTaskGroup(String taskId) {
        return taskMetas.get(taskId).group();
    }

    @Override
    public List<Task<?>> clearDoneTasks() {
        return taskMetas.values().stream().map(TaskMetadata::task).filter(Task::isFinished)
            .map(t -> taskMetas.remove(t.id).task()).collect(toList());
    }

    public void close() throws IOException {
        clearDoneTasks();
        eventConsumer.shutdown();
    }

    @Override
    public void clear() {
        taskMetas.clear();
    }
}
