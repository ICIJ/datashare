package org.icij.datashare.asynctasks;

import org.apache.commons.lang3.NotImplementedException;
import org.icij.datashare.asynctasks.bus.amqp.AmqpQueue;
import org.icij.datashare.asynctasks.bus.amqp.CancelledEvent;
import org.icij.datashare.asynctasks.bus.amqp.ErrorEvent;
import org.icij.datashare.asynctasks.bus.amqp.Event;
import org.icij.datashare.asynctasks.bus.amqp.ProgressEvent;
import org.icij.datashare.asynctasks.bus.amqp.ResultEvent;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.redisson.Redisson;
import org.redisson.RedissonBlockingQueue;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.command.CommandSyncService;
import org.redisson.liveobject.core.RedissonObjectBuilder;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.icij.datashare.asynctasks.TaskManagerRedis.EVENT_CHANNEL_NAME;

public class TaskSupplierRedis implements TaskSupplier {
    private final RTopic eventTopic;
    private final RedissonClient redissonClient;
    private final String taskQueueKey;

    public TaskSupplierRedis(RedissonClient redissonClient) {
        this(redissonClient, null);
    }

    public TaskSupplierRedis(RedissonClient redissonClient, String taskQueueKey) {
        this.eventTopic = redissonClient.getTopic(EVENT_CHANNEL_NAME);
        this.redissonClient = redissonClient;
        this.taskQueueKey = taskQueueKey;
    }

    @Override
    public Task get(int timeOut, TimeUnit timeUnit) throws InterruptedException {
        return taskQueue().poll(timeOut, timeUnit);
    }

    @Override
    public void consumeTasks(Consumer<Task> taskCallback) {
        throw new NotImplementedException("no loop is provided by redis databus. Use get(int, TimeUnit) method.");
    }

    @Override
    public Void progress(String taskId, double rate) {
        eventTopic.publish(new ProgressEvent(taskId, rate));
        return null;
    }

    @Override
    public void result(String taskId, byte[] result) {
        eventTopic.publish(new ResultEvent(taskId, result));
    }

    @Override
    public void canceled(Task task, boolean requeue) {
        eventTopic.publish(new CancelledEvent(task.id, requeue));
    }

    @Override
    public void error(String taskId, TaskError reason) {
        eventTopic.publish(new ErrorEvent(taskId, reason));
    }

    @Override
    public void addEventListener(Consumer<Event> callback) {
        eventTopic.addListener(Event.class, (channelString, message) -> callback.accept(message));
    }

    @Override
    public void waitForConsumer() {}

    private BlockingQueue<Task> taskQueue() {
        return this.taskQueueKey == null ?
            new RedissonBlockingQueue<>(new TaskManagerRedis.RedisCodec<>(Task.class), getCommandSyncService(), AmqpQueue.TASK.name(), redissonClient):
            new RedissonBlockingQueue<>(new TaskManagerRedis.RedisCodec<>(Task.class), getCommandSyncService(), String.format("%s.%s", AmqpQueue.TASK.name(), taskQueueKey), redissonClient);
    }

    private CommandSyncService getCommandSyncService() {
        return new CommandSyncService(((Redisson) redissonClient).getConnectionManager(), new RedissonObjectBuilder(redissonClient));
    }

    @Override
    public void close() throws IOException {
        eventTopic.removeAllListeners();
    }
}
