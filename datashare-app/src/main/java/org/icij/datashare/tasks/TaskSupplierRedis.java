package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.bus.amqp.CanceledEvent;
import org.icij.datashare.com.bus.amqp.Event;
import org.icij.datashare.com.bus.amqp.ProgressEvent;
import org.icij.datashare.com.bus.amqp.ResultEvent;
import org.icij.datashare.com.bus.amqp.TaskEvent;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import javax.inject.Inject;
import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.icij.datashare.tasks.TaskManagerRedis.EVENT_CHANNEL_NAME;

public class TaskSupplierRedis implements TaskSupplier {
    private final BlockingQueue<TaskView<?>> taskQueue;
    private final RTopic eventTopic;

    @Inject
    public TaskSupplierRedis(RedissonClient redissonClient, BlockingQueue<TaskView<?>> taskQueue) {
        this.taskQueue = taskQueue;
        this.eventTopic = redissonClient.getTopic(EVENT_CHANNEL_NAME);
    }

    TaskSupplierRedis(PropertiesProvider propertiesProvider, BlockingQueue<TaskView<?>> taskQueue) {
        this(new RedissonClientFactory().withOptions(Options.from(propertiesProvider.getProperties())).create(), taskQueue);
    }

    @Override
    public <V extends Serializable> TaskView<V> get(int timeOut, TimeUnit timeUnit) throws InterruptedException {
        return (TaskView<V>) taskQueue.poll(timeOut, timeUnit);
    }

    @Override
    public Void progress(String taskId, double rate) {
        eventTopic.publish(new ProgressEvent(taskId, rate));
        return null;
    }

    @Override
    public <V extends Serializable> void result(String taskId, V result) {
        eventTopic.publish(new ResultEvent<>(taskId, result));
    }

    @Override
    public void canceled(TaskView<?> task, boolean requeue) {
        eventTopic.publish(new CanceledEvent(task.id, requeue));
    }

    @Override
    public void error(String taskId, Throwable reason) {
        eventTopic.publish(new ResultEvent<>(taskId, reason));
    }

    @Override
    public void addEventListener(Consumer<TaskEvent> callback) {
        eventTopic.addListener(TaskEvent.class, (channelString, message) -> callback.accept(message));
    }
    @Override
    public void close() throws IOException {
        eventTopic.removeAllListeners();
    }
}
