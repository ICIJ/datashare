package org.icij.datashare.tasks;

import com.google.inject.Inject;
import org.icij.datashare.com.bus.amqp.AmqpConsumer;
import org.icij.datashare.com.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.com.bus.amqp.AmqpQueue;
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
        return (TaskView<V>) taskViewEvents.poll(timeOut, timeUnit).taskView;
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
    public void error(String taskId, Throwable throwable) {
        try {
            amqp.publish(AmqpQueue.TASK_RESULT, new ResultEvent<>(taskId, throwable));
        } catch (IOException e) {
            LoggerFactory.getLogger(getClass()).warn("cannot publish error {} for task {}", throwable, taskId);
        }
    }

    static class SupplierBufferingException extends RuntimeException { }
}
