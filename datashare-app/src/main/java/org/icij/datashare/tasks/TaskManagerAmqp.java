package org.icij.datashare.tasks;

import com.google.inject.Inject;
import org.icij.datashare.com.bus.amqp.AmqpConsumer;
import org.icij.datashare.com.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.com.bus.amqp.AmqpQueue;
import org.icij.datashare.com.bus.amqp.EventSaver;
import org.icij.datashare.com.bus.amqp.ProgressEvent;
import org.icij.datashare.com.bus.amqp.ResultEvent;
import org.icij.datashare.com.bus.amqp.TaskViewEvent;
import org.icij.datashare.user.User;
import org.redisson.Redisson;
import org.redisson.RedissonMap;
import org.redisson.api.RedissonClient;
import org.redisson.command.CommandSyncService;
import org.redisson.liveobject.core.RedissonObjectBuilder;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Phaser;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

public class TaskManagerAmqp implements TaskManager {
    private final Map<String, TaskView<?>> tasks;
    private final AmqpInterlocutor amqp;
    private final AmqpConsumer<ProgressEvent, EventSaver<ProgressEvent>> eventConsumer;
    private final AmqpConsumer<ResultEvent<? extends Serializable>, EventSaver<ResultEvent<? extends Serializable>>> resultConsumer;

    @Inject
    public TaskManagerAmqp(AmqpInterlocutor amqp, RedissonClient redissonClient) throws IOException {
        this(amqp, redissonClient, null);
    }

    TaskManagerAmqp(AmqpInterlocutor amqp, RedissonClient redissonClient, Runnable eventCallback) throws IOException {
        this.amqp = amqp;
        CommandSyncService commandSyncService = new CommandSyncService(((Redisson) redissonClient).getConnectionManager(), new RedissonObjectBuilder(redissonClient));
        tasks = new RedissonMap<>(new TaskManagerRedis.TaskViewCodec(), commandSyncService, "ds:tasks", redissonClient, null, null);

        eventConsumer = new AmqpConsumer<>(amqp, event -> {
            TaskView<?> taskView = tasks.get(event.taskId);
            taskView.setProgress(event.rate);
            tasks.put(event.taskId, taskView);
            ofNullable(eventCallback).ifPresent(Runnable::run);
        }, AmqpQueue.EVENT, ProgressEvent.class);
        eventConsumer.consumeEvents();

        resultConsumer = new AmqpConsumer<>(amqp, event -> {
            TaskView<? extends Serializable> taskView = (TaskView<? extends Serializable>) tasks.get(event.taskId);
            if (Throwable.class.isAssignableFrom(event.result.getClass())) {
                taskView.setError((Throwable) event.result);
            } else {
                taskView.setResult(event.result);
            }
            tasks.put(event.taskId, taskView);
            ofNullable(eventCallback).ifPresent(Runnable::run);
        }, AmqpQueue.TASK_RESULT, (Class<ResultEvent<? extends Serializable>>) (Class<?>) ResultEvent.class);
        resultConsumer.consumeEvents();
    }

    @Override
    public <V> TaskView<V> startTask(Callable<V> task, Runnable callback) {
        return null;
    }

    @Override
    public <V> TaskView<V> startTask(String taskName, User user, Map<String, Object> properties) throws IOException {
        TaskView<V> taskView = new TaskView<>(taskName, user, properties);
        save(taskView);
        amqp.publish(AmqpQueue.TASK, new TaskViewEvent(taskView));
        return taskView;
    }

    @Override
    public <V> TaskView<V> startTask(Callable<V> task) {
        return null;
    }

    @Override
    public boolean stopTask(String taskId) {
        return false;
    }

    @Override
    public Map<String, Boolean> stopAllTasks(User user) {
        return null;
    }

    @Override
    public <V> TaskView<V> clearTask(String taskId) {
        return null;
    }

    @Override
    public boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException {
        return false;
    }

    void save(TaskView<?> task) {
        tasks.put(task.id, task);
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
}
