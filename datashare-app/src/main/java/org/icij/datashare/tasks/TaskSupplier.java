package org.icij.datashare.tasks;

import java.util.concurrent.TimeUnit;

public interface TaskSupplier extends TaskModifier {
    <V> TaskView<V> get(int timeOut, TimeUnit timeUnit) throws InterruptedException;
}
