package org.icij.datashare.asynctasks;

import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.asynctasks.bus.amqp.AmqpQueue;
import org.icij.datashare.asynctasks.bus.amqp.CancelEvent;
import org.icij.datashare.asynctasks.bus.amqp.ResultEvent;
import org.icij.datashare.asynctasks.bus.amqp.TaskEvent;
import org.icij.datashare.asynctasks.bus.amqp.AmqpConsumer;
import org.icij.datashare.asynctasks.bus.amqp.TaskCreation;
import org.icij.datashare.user.User;

import java.io.IOException;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

public class TaskManagerAmqp implements TaskManager {
    private final Map<String, TaskView<?>> tasks;
    private final AmqpInterlocutor amqp;
    private final AmqpConsumer<TaskEvent, Consumer<TaskEvent>> eventConsumer;
    private final AmqpConsumer<ResultEvent<? extends Serializable>, Consumer<ResultEvent<? extends Serializable>>> resultConsumer;

    public TaskManagerAmqp(AmqpInterlocutor amqp, Map<String, TaskView<?>> tasks) throws IOException {
        this(amqp, tasks, null);
    }

    public TaskManagerAmqp(AmqpInterlocutor amqp, Map<String, TaskView<?>> tasks, Runnable eventCallback) throws IOException {
        this.amqp = amqp;
        this.tasks = tasks;
        eventConsumer = new AmqpConsumer<>(amqp, event ->
                ofNullable(TaskManager.super.handleAck(event)).flatMap(t ->
                        ofNullable(eventCallback)).ifPresent(Runnable::run), AmqpQueue.EVENT, TaskEvent.class).consumeEvents();

        resultConsumer = new AmqpConsumer<>(amqp, event ->
                ofNullable(TaskManager.super.handleAck(event)).flatMap(t ->
                        ofNullable(eventCallback)).ifPresent(Runnable::run), AmqpQueue.TASK_RESULT,
                (Class<ResultEvent<? extends Serializable>>) (Class<?>) ResultEvent.class).consumeEvents();
    }

    @Override
    public boolean stopTask(String taskId) {
        TaskView<?> taskView = tasks.get(taskId);
        if (taskView != null) {
            try {
                amqp.publish(AmqpQueue.RUNNER_EVENT, new CancelEvent(taskId, false));
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
    public <V> TaskView<V> clearTask(String taskId) {
        return null;
    }

    @Override
    public boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException {
        return false;
    }

    public void save(TaskView<?> task) {
        tasks.put(task.id, task);
    }

    @Override
    public void enqueue(TaskView<?> task) throws IOException {
        amqp.publish(AmqpQueue.TASK, new TaskCreation(task));
    }

    @Override
    public <V> TaskView<V> getTask(String taskId) {
        return (TaskView<V>) tasks.get(taskId);
    }

    @Override
    public List<TaskView<?>> getTasks() {
        return new LinkedList<>(tasks.values());
    }

    @Override
    public List<TaskView<?>> getTasks(User user, Pattern pattern) {
        return TaskManager.getTasks(tasks.values().stream(), user, pattern);
    }

    @Override
    public List<TaskView<?>> clearDoneTasks() {
        return tasks.values().stream().filter(f -> f.getState() != TaskView.State.RUNNING).map(t -> tasks.remove(t.id)).collect(toList());
    }

    public void close() throws IOException {
        clearDoneTasks();
        resultConsumer.cancel();
        eventConsumer.cancel();
    }

    @Override
    public void clear() {
        tasks.clear();
    }
}
