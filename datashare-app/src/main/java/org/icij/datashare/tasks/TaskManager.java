package org.icij.datashare.tasks;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.icij.datashare.user.User;

public interface TaskManager extends TaskRepository {
    TaskViewInterface<Void> startTask(Runnable task);
    <V> TaskViewInterface<V> startTask(User user, String taskType, Map<String, Object> inputs);
    <V> TaskViewInterface<V> startTask(Callable<V> task, Runnable callback);
    <V> TaskViewInterface<V> startTask(Callable<V> task, Map<String, Object> properties);
    <V> TaskViewInterface<V> startTask(Callable<V> task);
    boolean stopTask(String taskName);
    <V> TaskViewInterface<?> clearTask(String taskName);
    boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException;
}
