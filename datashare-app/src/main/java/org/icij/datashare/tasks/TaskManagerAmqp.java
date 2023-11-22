package org.icij.datashare.tasks;

import net.codestory.http.security.User;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class TaskManagerAmqp implements TaskManager {

    @Override
    public <V> TaskView<V> startTask(Callable<V> task, Runnable callback) {
        return null;
    }

    @Override
    public <V> TaskView<V> startTask(String taskName, Map<String, Object> properties) {
        return null;
    }

    @Override
    public <V> TaskView<V> startTask(Callable<V> task) {
        return null;
    }

    @Override
    public boolean stopTask(String taskId) {
        return false;
    }

    @Override
    public Map<String, Boolean> stopAllTasks(User user) {
        return null;
    }

    @Override
    public <V> TaskView<V> clearTask(String taskId) {
        return null;
    }

    @Override
    public boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException {
        return false;
    }

    @Override
    public <V> TaskView<V> getTask(String taskId) {
        return null;
    }

    @Override
    public List<TaskView<?>> getTasks() {
        return null;
    }

    @Override
    public List<TaskView<?>> getTasks(User user, Pattern pattern) {
        return null;
    }

    @Override
    public List<TaskView<?>> clearDoneTasks() {
        return null;
    }
}
