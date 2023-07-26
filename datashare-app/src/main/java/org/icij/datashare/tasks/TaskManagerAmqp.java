package org.icij.datashare.tasks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public class TaskManagerAmqp implements TaskManager {
    public TaskManagerAmqp(String hostname, int port) {
    }

    @Override
    public TaskView<Void> startTask(Runnable task) {
        return null;
    }

    @Override
    public <V> TaskView<V> startTask(Callable<V> task, Runnable callback) {
        return null;
    }

    @Override
    public <V> TaskView<V> startTask(Callable<V> task, Map<String, Object> properties) {
        return null;
    }

    @Override
    public <V> TaskView<V> startTask(Callable<V> task) {
        return null;
    }

    @Override
    public boolean stopTask(String taskName) {
        return false;
    }

    @Override
    public <V> TaskView<?> clearTask(String taskName) {
        return null;
    }

    @Override
    public boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException {
        return false;
    }

    @Override
    public <V> Void save(TaskView<V> task) {
        return null;
    }

    @Override
    public TaskView<?> get(String id) {
        return null;
    }

    @Override
    public List<TaskView<?>> get() {
        return null;
    }

    @Override
    public List<TaskView<?>> clearDoneTasks() {
        return null;
    }
}
