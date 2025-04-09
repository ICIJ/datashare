package org.icij.datashare.asynctasks;

import org.apache.commons.lang3.NotImplementedException;
import org.icij.datashare.asynctasks.bus.amqp.AmqpConsumer;
import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.asynctasks.bus.amqp.AmqpQueue;
import org.icij.datashare.asynctasks.bus.amqp.CancelledEvent;
import org.icij.datashare.asynctasks.bus.amqp.ErrorEvent;
import org.icij.datashare.asynctasks.bus.amqp.Event;
import org.icij.datashare.asynctasks.bus.amqp.ProgressEvent;
import org.icij.datashare.asynctasks.bus.amqp.ResultEvent;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;


public class TaskSupplierAmqp implements TaskSupplier {
    final AmqpConsumer<Task, Consumer<Task>> consumer;
    final AmqpConsumer<Event, Consumer<Event>> eventConsumer;
    final List<Consumer<Event>> eventCallbackList = new LinkedList<>();
    private final AmqpInterlocutor amqp;

    public TaskSupplierAmqp(AmqpInterlocutor amqp) throws IOException {
        this(amqp, null);
    }

    public TaskSupplierAmqp(AmqpInterlocutor amqp, String routingKey) throws IOException {
        this.amqp = amqp;
        this.consumer = routingKey == null ?
                new AmqpConsumer<>(amqp, null, AmqpQueue.TASK, Task.class):
                new AmqpConsumer<>(amqp, null, AmqpQueue.TASK, Task.class, routingKey);
        this.eventConsumer = new AmqpConsumer<>(amqp, this::handleEvent, AmqpQueue.WORKER_EVENT, Event.class).consumeEvents();
    }

    @Override
    public Void progress(String taskId, double rate) {
        try {
            amqp.publish(AmqpQueue.MANAGER_EVENT, new ProgressEvent(taskId, rate));
        } catch (IOException e) {
            LoggerFactory.getLogger(getClass()).warn("cannot publish progress {} for task {}", rate, taskId);
        }
        return null;
    }

    @Override
    public Task get(int timeOut, TimeUnit timeUnit) throws InterruptedException {
        throw new NotImplementedException("no get method for AMQP, use consumeTasks(Consumer<Task>");
    }

    @Override
    public void consumeTasks(Consumer<Task> taskCallback) {
        consumer.consumeEvents(taskCallback);
    }

    @Override
    public void result(String taskId, byte[] result) {
        try {
            amqp.publish(AmqpQueue.MANAGER_EVENT, new ResultEvent(taskId, result));
        } catch (IOException e) {
            LoggerFactory.getLogger(getClass()).warn("cannot publish result {} for task {}", result, taskId);
        }
    }

    @Override
    public void canceled(Task task, boolean requeue) {
        try {
            // TODO: align behavior here with Python where calling this directly nacks the task
            //  instead of sending a message to the taskManager which is then responsible to handle
            //  it. This avoid complex communication (worker)->(manager)->(broker) and then
            //  (manager)->(worker) (to confirm the cancellation to the worker). Instead we have
            //  (worker)->(broker) and then (worker)->(*) (broadcast the cancellation).
            amqp.publish(AmqpQueue.MANAGER_EVENT, new CancelledEvent(task.id, requeue));
        } catch (IOException e) {
            LoggerFactory.getLogger(getClass()).warn("cannot publish cancelled for task {}", task.id);
        }
    }

    @Override
    public void error(String taskId, TaskError taskError) {
        try {
            amqp.publish(AmqpQueue.MANAGER_EVENT,new ErrorEvent(taskId, taskError));
        } catch (IOException e) {
            LoggerFactory.getLogger(getClass()).warn("cannot publish error for task {}", taskId);
        }
    }

    private void handleEvent(Event taskEvent) {
        eventCallbackList.forEach(c -> c.accept(taskEvent));
    }

    @Override
    public void addEventListener(Consumer<Event> callback) {
        eventCallbackList.add(callback);
    }

    @Override
    public void waitForConsumer() {
        consumer.waitUntilChannelIsClosed();
    }

    @Override
    public void close() throws IOException {
        consumer.shutdown();
        eventConsumer.shutdown();
    }
}
