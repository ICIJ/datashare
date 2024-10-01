package org.icij.datashare.asynctasks;

import org.apache.commons.lang3.NotImplementedException;
import org.icij.datashare.asynctasks.bus.amqp.AmqpConsumer;
import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.asynctasks.bus.amqp.AmqpQueue;
import org.icij.datashare.asynctasks.bus.amqp.CancelledEvent;
import org.icij.datashare.asynctasks.bus.amqp.ErrorEvent;
import org.icij.datashare.asynctasks.bus.amqp.ProgressEvent;
import org.icij.datashare.asynctasks.bus.amqp.ResultEvent;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.asynctasks.bus.amqp.TaskEvent;
import org.icij.datashare.tasks.RoutingStrategy;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;


public class TaskSupplierAmqp implements TaskSupplier {
    final AmqpConsumer<Task, Consumer<Task>> consumer;
    final AmqpConsumer<TaskEvent, Consumer<TaskEvent>> eventConsumer;
    final List<Consumer<TaskEvent>> eventCallbackList = new LinkedList<>();
    private final AmqpInterlocutor amqp;

    public TaskSupplierAmqp(AmqpInterlocutor amqp) throws IOException {
        this(amqp, RoutingStrategy.UNIQUE, null);
    }

    public TaskSupplierAmqp(AmqpInterlocutor amqp, RoutingStrategy routingStrategy, String routingKey) throws IOException {
        this.amqp = amqp;
        this.consumer = routingStrategy == RoutingStrategy.UNIQUE ?
                new AmqpConsumer<>(amqp, null, AmqpQueue.TASK, Task.class):
                new AmqpConsumer<>(amqp, null, AmqpQueue.TASK, Task.class, routingKey);
        this.eventConsumer = new AmqpConsumer<>(amqp, this::handleEvent, AmqpQueue.WORKER_EVENT, TaskEvent.class).consumeEvents();
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
    public <V extends Serializable> Task<V> get(int timeOut, TimeUnit timeUnit) throws InterruptedException {
        throw new NotImplementedException("no get method for AMQP, use consumeTasks(Consumer<Task<V>>");
    }

    @Override
    public void consumeTasks(Consumer<Task> taskCallback) {
        consumer.consumeEvents(taskCallback);
    }

    @Override
    public <V extends Serializable> void result(String taskId, V result) {
        try {
            amqp.publish(AmqpQueue.MANAGER_EVENT, result.getClass().isAssignableFrom(TaskError.class) ?
                    new ErrorEvent(taskId, (TaskError) result):
                    new ResultEvent<>(taskId, result));
        } catch (IOException e) {
            LoggerFactory.getLogger(getClass()).warn("cannot publish result {} for task {}", result, taskId);
        }
    }

    @Override
    public void canceled(Task<?> task, boolean requeue) {
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
    public void waitForConsumer() {
        consumer.waitUntilChannelIsClosed();
    }

    @Override
    public void close() throws IOException {
        consumer.shutdown();
    }
}
