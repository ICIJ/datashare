package org.icij.datashare.asynctasks;

import org.apache.commons.lang3.NotImplementedException;
import org.icij.datashare.asynctasks.bus.amqp.AmqpQueue;
import org.icij.datashare.asynctasks.bus.amqp.CancelledEvent;
import org.icij.datashare.asynctasks.bus.amqp.ErrorEvent;
import org.icij.datashare.asynctasks.bus.amqp.ProgressEvent;
import org.icij.datashare.asynctasks.bus.amqp.ResultEvent;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.asynctasks.bus.amqp.TaskEvent;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.icij.datashare.asynctasks.TaskManagerRedis.EVENT_CHANNEL_NAME;

public class TaskSupplierRedis implements TaskSupplier {
    private final BlockingQueue<Task<?>> taskQueue;
    private final RTopic eventTopic;

    public TaskSupplierRedis(RedissonClient redissonClient, BlockingQueue<Task<?>> taskQueue) {
        this.taskQueue = taskQueue;
        this.eventTopic = redissonClient.getTopic(EVENT_CHANNEL_NAME);
    }

    @Override
    public <V extends Serializable> Task<V> get(int timeOut, TimeUnit timeUnit) throws InterruptedException {
        return (Task<V>) taskQueue.poll(timeOut, timeUnit);
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
    public <V extends Serializable> void result(String taskId, V result) {
        eventTopic.publish(result.getClass().isAssignableFrom(TaskError.class) ?
                new ErrorEvent(taskId, (TaskError) result):
                new ResultEvent<>(taskId, result));
    }

    @Override
    public void canceled(Task<?> task, boolean requeue) {
        eventTopic.publish(new CancelledEvent(task.id, requeue));
    }

    @Override
    public void error(String taskId, TaskError reason) {
        result(taskId, reason);
    }

    @Override
    public void addEventListener(Consumer<TaskEvent> callback) {
        eventTopic.addListener(TaskEvent.class, (channelString, message) -> callback.accept(message));
    }

    @Override
    public void waitForConsumer() {}

    @Override
    public void close() throws IOException {
        eventTopic.removeAllListeners();
    }
}
