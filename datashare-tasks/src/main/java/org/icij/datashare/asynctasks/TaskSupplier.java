package org.icij.datashare.asynctasks;

import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.asynctasks.bus.amqp.TaskEvent;

import java.io.Closeable;
import java.io.Serializable;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public interface TaskSupplier extends TaskModifier, Closeable {
    <V extends Serializable> TaskView<V> get(int timeOut, TimeUnit timeUnit) throws InterruptedException;
    <V extends Serializable> void result(String taskId, V result);
    // TODO: align the naming here. "cancel" is a bit misleading, negativelyAcknowledge would be
    //  more appropriate here since this can be called when errors occur
    //  (vs. cancellation asked by user)
    // TODO: align behavior here with Python where calling this directly nacks the task
    //  instead of sending a message to the taskManager which is then responsible to handle
    //  it. This avoid complex communication (worker)->(manager)->(broker) and then
    //  (manager)->(worker) (to confirm the cancellation to the worker). Instead we have
    //  (worker)->(broker) and then (worker)->(*) (broadcast the cancellation).
    void canceled(TaskView<?> taskView, boolean requeue);
    void error(String taskId, TaskError reason);
    void addEventListener(Consumer<TaskEvent> callback);
}
