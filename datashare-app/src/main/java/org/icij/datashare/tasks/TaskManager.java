package org.icij.datashare.tasks;


import org.icij.datashare.user.User;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.icij.datashare.tasks.TaskView.State.QUEUED;
import static org.icij.datashare.tasks.TaskView.State.RUNNING;

public interface TaskManager extends Closeable {
    <V> TaskView<V> startTask(String taskName, User user, Map<String, Object> properties) throws IOException;
    <V> TaskView<V> startTask(String id, String taskName, User user) throws IOException;
    boolean stopTask(String taskId);
    <V> TaskView<V> clearTask(String taskId);
    boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException;
    <V> TaskView<V> getTask(String taskId);
    List<TaskView<?>> getTasks();
    List<TaskView<?>> getTasks(User user, Pattern pattern);
    // do we need getResult(taskId) getError(taskId) ?
    // to avoid serializing/deserializing heavy results objects
    List<TaskView<?>> clearDoneTasks();
    void clear();

    static List<TaskView<?>> getTasks(Stream<TaskView<?>> stream, User user, Pattern pattern) {
        return stream.
                filter(t -> user.equals(t.getUser())).
                filter(t -> pattern.matcher(t.name).matches()).
                collect(toList());
    }

    default Map<String, Boolean> stopAllTasks(User user) {
        return getTasks().stream().
                filter(t -> user.equals(t.getUser())).
                filter(t -> t.getState() == RUNNING || t.getState() == QUEUED).collect(
                        toMap(t -> t.id, t -> stopTask(t.id)));
    }
}
