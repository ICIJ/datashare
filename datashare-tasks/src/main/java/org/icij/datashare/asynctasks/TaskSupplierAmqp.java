package org.icij.datashare.asynctasks;

import org.icij.datashare.asynctasks.bus.amqp.AmqpConsumer;
import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.asynctasks.bus.amqp.AmqpQueue;
import org.icij.datashare.asynctasks.bus.amqp.CancelledEvent;
import org.icij.datashare.asynctasks.bus.amqp.ProgressEvent;
import org.icij.datashare.asynctasks.bus.amqp.ResultEvent;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.asynctasks.bus.amqp.TaskEvent;
import org.icij.datashare.asynctasks.bus.amqp.TaskViewEvent;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.util.Optional.ofNullable;

public class TaskSupplierAmqp implements TaskSupplier {
    private final BlockingQueue<TaskViewEvent> taskViewEvents = new ArrayBlockingQueue<>(1024);
    final AmqpConsumer<TaskViewEvent, Consumer<TaskViewEvent>> consumer;
    final AmqpConsumer<TaskEvent, Consumer<TaskEvent>> eventConsumer;
    final List<Consumer<TaskEvent>> eventCallbackList = new LinkedList<>();
    private final AmqpInterlocutor amqp;

    public TaskSupplierAmqp(AmqpInterlocutor amqp) throws IOException {
        this.amqp = amqp;
        consumer = new AmqpConsumer<>(amqp, event -> {
            if (!taskViewEvents.offer(event)) {
                throw new SupplierBufferingException();
            }
        }, AmqpQueue.TASK, TaskViewEvent.class).consumeEvents();
        eventConsumer = new AmqpConsumer<>(amqp, this::handleEvent, AmqpQueue.EVENT, TaskEvent.class).consumeEvents();
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
        try {
            // TODO: align behavior here with Python where calling this directly nacks the task
            //  instead of sending a message to the taskManager which is then responsible to handle
            //  it. This avoid complex communication (worker)->(manager)->(broker) and then
            //  (manager)->(worker) (to confirm the cancellation to the worker). Instead we have
            //  (worker)->(broker) and then (worker)->(*) (broadcast the cancellation).
            amqp.publish(AmqpQueue.EVENT, new CancelledEvent(task.id, requeue));
        } catch (IOException e) {
            LoggerFactory.getLogger(getClass()).warn("cannot publish cancelled for task {}", task.id);
        }
    }

    @Override
    public void error(String taskId, TaskError taskError) {
        result(taskId, taskError);
    }

    private void handleEvent(TaskEvent taskEvent) {
        eventCallbackList.forEach(c -> c.accept(taskEvent));
    }

    @Override
    public void addEventListener(Consumer<TaskEvent> callback) {
        eventCallbackList.add(callback);
    }

    @Override
    public void close() throws IOException {
        consumer.cancel();
    }

    static class SupplierBufferingException extends RuntimeException { }
}
