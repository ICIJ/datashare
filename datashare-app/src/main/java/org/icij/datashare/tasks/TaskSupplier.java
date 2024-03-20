package org.icij.datashare.tasks;

import org.icij.datashare.com.bus.amqp.TaskEvent;

import java.io.Closeable;
import java.io.Serializable;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public interface TaskSupplier extends TaskModifier, Closeable {
    <V extends Serializable> TaskView<V> get(int timeOut, TimeUnit timeUnit) throws InterruptedException;
    <V extends Serializable> void result(String taskId, V result);
    void canceled(TaskView<?> taskView, boolean requeue);
    void error(String taskId, Throwable reason);
    void addEventListener(Consumer<TaskEvent> callback);
}
