package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.icij.datashare.com.bus.amqp.AmqpConsumer;
import org.icij.datashare.com.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.com.bus.amqp.AmqpQueue;
import org.icij.datashare.com.bus.amqp.Event;
import org.icij.datashare.com.bus.amqp.EventSaver;
import org.icij.datashare.com.bus.amqp.ProgressEvent;
import org.icij.datashare.com.bus.amqp.ResultEvent;
import org.icij.datashare.com.bus.amqp.TaskViewEvent;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.util.Optional.ofNullable;

@Singleton
public class TaskSupplierAmqp implements TaskSupplier {
    private final BlockingQueue<TaskViewEvent> taskViewEvents = new ArrayBlockingQueue<>(1024);
    final AmqpConsumer<TaskViewEvent, EventSaver<TaskViewEvent>> consumer;
    private final AmqpInterlocutor amqp;

    @Inject
    public TaskSupplierAmqp(AmqpInterlocutor amqp) throws IOException {
        this.amqp = amqp;
        consumer = new AmqpConsumer<>(amqp, event -> {
            if (!taskViewEvents.offer(event)) {
                throw new SupplierBufferingException();
            }
        }, AmqpQueue.TASK, TaskViewEvent.class);
        consumer.consumeEvents();
    }

    @Override
    public Void progress(String taskId, double rate) {
        try {
            amqp.publish(AmqpQueue.EVENT, new ProgressEvent(taskId, rate));
        } catch (IOException e) {
            LoggerFactory.getLogger(getClass()).warn("cannot publish progress {} for task {}", rate, taskId);
        }
        return null;
    }

    @Override
    public <V extends Serializable> TaskView<V> get(int timeOut, TimeUnit timeUnit) throws InterruptedException {
        return (TaskView<V>) ofNullable(taskViewEvents.poll(timeOut, timeUnit)).map(te -> te.taskView).orElse(null);
    }

    @Override
    public <V extends Serializable> void result(String taskId, V result) {
        try {
            amqp.publish(AmqpQueue.TASK_RESULT, new ResultEvent<>(taskId, result));
        } catch (IOException e) {
            LoggerFactory.getLogger(getClass()).warn("cannot publish result {} for task {}", result, taskId);
        }
    }

    @Override
    public void canceled(TaskView<?> task, boolean requeue) {
    }

    @Override
    public void error(String taskId, Throwable throwable) {
        result(taskId, throwable);
    }

    @Override
    public void addEventListener(Consumer<Event> callback) {
        // TODO
    }

    @Override
    public void close() throws IOException {
        consumer.cancel();
    }

    static class SupplierBufferingException extends RuntimeException { }
}
