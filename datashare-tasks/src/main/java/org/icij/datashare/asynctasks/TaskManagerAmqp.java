package org.icij.datashare.asynctasks;

import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.asynctasks.bus.amqp.AmqpQueue;
import org.icij.datashare.asynctasks.bus.amqp.CancelEvent;
import org.icij.datashare.asynctasks.bus.amqp.TaskEvent;
import org.icij.datashare.asynctasks.bus.amqp.AmqpConsumer;
import org.icij.datashare.user.User;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

public class TaskManagerAmqp implements TaskManager {
    private final Map<String, Task<?>> tasks;
    private final AmqpInterlocutor amqp;
    private final AmqpConsumer<TaskEvent, Consumer<TaskEvent>> eventConsumer;

    public TaskManagerAmqp(AmqpInterlocutor amqp, Map<String, Task<?>> tasks) throws IOException {
        this(amqp, tasks, null);
    }

    public TaskManagerAmqp(AmqpInterlocutor amqp, Map<String, Task<?>> tasks, Runnable eventCallback) throws IOException {
        this.amqp = amqp;
        this.tasks = tasks;
        eventConsumer = new AmqpConsumer<>(amqp, event ->
                ofNullable(TaskManager.super.handleAck(event)).flatMap(t ->
                        ofNullable(eventCallback)).ifPresent(Runnable::run), AmqpQueue.MANAGER_EVENT, TaskEvent.class).consumeEvents();
    }

    @Override
    public boolean stopTask(String taskId) {
        Task<?> taskView = tasks.get(taskId);
        if (taskView != null) {
            try {
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
        return null;
    }

    @Override
    public boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException {
        return false;
    }

    public boolean save(Task<?> task) {
        Task<?> oldVal = tasks.put(task.id, task);
        return oldVal == null;
    }

    @Override
    public void enqueue(Task<?> task) throws IOException {
        amqp.publish(AmqpQueue.TASK, task);
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
    public List<Task<?>> getTasks(User user, Pattern pattern) {
        return TaskManager.getTasks(tasks.values().stream(), user, pattern);
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
}
