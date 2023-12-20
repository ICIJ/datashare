package org.icij.datashare.tasks;

import java.io.Closeable;
import java.io.Serializable;
import java.util.concurrent.TimeUnit;

public interface TaskSupplier extends TaskModifier, Closeable {
    <V extends Serializable> TaskView<V> get(int timeOut, TimeUnit timeUnit) throws InterruptedException;
    <V extends Serializable> void result(String taskId, V result);
    void error(String taskId, Throwable reason);
    // is there a need for another recoverable error method?
}
