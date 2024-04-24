package org.icij.datashare.asynctasks;

import org.icij.datashare.asynctasks.bus.amqp.CancelledEvent;
import org.icij.datashare.asynctasks.bus.amqp.ProgressEvent;
import org.icij.datashare.asynctasks.bus.amqp.ResultEvent;
import org.icij.datashare.asynctasks.bus.amqp.TaskEvent;
import org.icij.datashare.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

public interface TaskManager extends Closeable {
    Logger logger = LoggerFactory.getLogger(TaskManager.class);

    boolean stopTask(String taskId);
    <V> TaskView<V> clearTask(String taskId);
    boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException;
    <V> TaskView<V> getTask(String taskId);
    List<TaskView<?>> getTasks();
    List<TaskView<?>> getTasks(User user, Pattern pattern);
    List<TaskView<?>> clearDoneTasks();
    void clear();
    void save(TaskView<?> task);
    void enqueue(TaskView<?> task) throws IOException;

    static List<TaskView<?>> getTasks(Stream<TaskView<?>> stream, User user, Pattern pattern) {
        return stream.
                filter(t -> user.equals(t.getUser())).
                filter(t -> pattern.matcher(t.name).matches()).
                collect(toList());
    }

    default Map<String, Boolean> stopAllTasks(User user) {
        return getTasks().stream().
                filter(t -> user.equals(t.getUser())).
                filter(t -> t.getState() == TaskView.State.RUNNING || t.getState() == TaskView.State.QUEUED).collect(
                        toMap(t -> t.id, t -> stopTask(t.id)));
    }


    default  <V> TaskView<V> startTask(String taskName, User user, Map<String, Object> properties) throws IOException {
        return startTask(new TaskView<>(taskName, user, properties));
    }

    default  <V> TaskView<V> startTask(String id, String taskName, User user) throws IOException {
        return startTask(new TaskView<>(id, taskName, user, new HashMap<>()));
    }

    default <V> TaskView<V> startTask(TaskView<V> taskView) throws IOException {
        taskView.queue();
        save(taskView);
        enqueue(taskView);
        return taskView;
    }

    default <V extends Serializable> TaskView<V> setResult(ResultEvent<V> e) {
        TaskView<V> taskView = getTask(e.taskId);
        if (taskView != null) {
            logger.info("result event for {}", e.taskId);
            // TODO: this won't work for other languages than Java unless we manage to deserialize
            //  Exception from other Languages in Java
            if (e.result instanceof Throwable) {
                taskView.setError((Throwable) e.result);
            } else {
                taskView.setResult(e.result);
            }
            save(taskView);
        } else {
            logger.warn("no task found for result event {}", e.taskId);
        }
        return taskView;
    }

    default TaskView<?> setCanceled(CancelledEvent e) {
        TaskView<?> taskView = getTask(e.taskId);
        if (taskView != null) {
            logger.info("canceled event for {}", e.taskId);
            taskView.cancel();
            save(taskView);
            if (e.requeue) {
                try {
                    enqueue(taskView);
                } catch (IOException ex) {
                    logger.error("error while reposting canceled event", ex);
                }
            }
        } else {
            logger.warn("no task found for canceled event {}", e.taskId);
        }
        return taskView;
    }

    default TaskView<?> setProgress(ProgressEvent e) {
        logger.debug("progress event for {}", e.taskId);
        TaskView<?> taskView = getTask(e.taskId);
        if (taskView != null) {
            taskView.setProgress(e.rate);
            save(taskView);
        }
        return taskView;
    }

    default <V extends Serializable> TaskView<?> handleAck(TaskEvent e) {
        if (e instanceof CancelledEvent) {
            return setCanceled((CancelledEvent) e);
        }
        // TODO: difference with Python where there's dedicated message for error (separate from result)
        if (e instanceof ResultEvent) {
            return setResult(((ResultEvent<V>) e));
        }
        // TODO: difference with Python where we have TaskEvent which can hold more than progress
        //  updates
        if (e instanceof ProgressEvent) {
            return setProgress((ProgressEvent)e);
        }
        return null;
    }
}
