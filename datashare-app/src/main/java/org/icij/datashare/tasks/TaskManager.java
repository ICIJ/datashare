package org.icij.datashare.tasks;

import net.codestory.http.security.User;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

public interface TaskManager {
    <V> TaskView<V> startTask(Callable<V> task, Runnable callback);
    <V> TaskView<V> startTask(Callable<V> task, Map<String, Object> properties);
    <V> TaskView<V> startTask(Callable<V> task);
    boolean stopTask(String taskId);
    Map<String, Boolean> stopAllTasks(User user);
    <V> TaskView<V> clearTask(String taskId);
    boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException;
    <V> TaskView<V> getTask(String taskId);
    List<TaskView<?>> getTasks();
    List<TaskView<?>> getTasks(User user, Pattern pattern);
    // do we need getResult(taskId) getError(taskId) ?
    // to avoid serializing/deserializing heavy results objects
    List<TaskView<?>> clearDoneTasks();

    static List<TaskView<?>> getTasks(Stream<TaskView<?>> stream, User user, Pattern pattern) {
        return stream.
                filter(t -> user.equals(t.getUser())).
                filter(t -> pattern.matcher(t.id).matches()).
                collect(toList());
    }
}
