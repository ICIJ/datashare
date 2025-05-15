package org.icij.datashare.asynctasks;

import java.io.IOException;
import org.icij.datashare.asynctasks.bus.amqp.Event;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;

import java.io.Closeable;
import java.io.Serializable;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public interface TaskSupplier extends TaskModifier, Closeable {
    /**
     * Blocking call waiting for the next Task. It is awakened by the event
     * layer (epoll, kqueue, ...). We provide a timeout to reset the socket
     * (firewall long connection breaking issues).
     * @param timeOut: amount of #TimeUnit for exiting the
     * @param timeUnit: unit of time
     * @return a Task
     */
    Task get(int timeOut, TimeUnit timeUnit) throws InterruptedException;
    /**
     * Inversion of loop control (compared to the {@link #get(int, TimeUnit)} method)
     * for frameworks that provide their own loop like RabbitMq java API
     * (the loop does connection management for example).
     * @param taskCallback: the callback provided to the loop
     */
    void consumeTasks(Consumer<Task> taskCallback);

    /**
     * method called to send a result into the databus.
     * @param taskId: id of the task
     * @param result: result of the task
     */
    void result(String taskId, byte[] result);
    /**
     * method called to send an error in case a task is failing.
     * @param taskId: id of the task
     * @param reason: the stacktrace for the error
     */
    void error(String taskId, TaskError reason);
    /**
     * method called to send an acknowledgement of cancellation asked by a task manager.
     * @param task: the task event that has been asked for cancellation
     * @param requeue: requeue the task
     */
    void canceled(Task task, boolean requeue);

    /**
     * Method to add a listener to the Event sent by the task manager.
     *
     * @param callback: callback to handle the Task Events
     */
    void addEventListener(Consumer<Event> callback);

    void waitForConsumer();
}
