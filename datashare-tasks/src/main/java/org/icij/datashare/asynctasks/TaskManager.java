package org.icij.datashare.asynctasks;

import org.icij.datashare.asynctasks.bus.amqp.CancelledEvent;
import org.icij.datashare.asynctasks.bus.amqp.ErrorEvent;
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
    <V> Task<V> clearTask(String taskId);
    boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException;
    <V> Task<V> getTask(String taskId);
    List<Task<?>> getTasks();
    List<Task<?>> getTasks(User user, Pattern pattern);
    List<Task<?>> clearDoneTasks();
    void clear();
    boolean save(Task<?> task);
    void enqueue(Task<?> task) throws IOException;

    static List<Task<?>> getTasks(Stream<Task<?>> stream, User user, Pattern pattern) {
        return stream.
                filter(t -> user.equals(t.getUser())).
                filter(t -> pattern.matcher(t.name).matches()).
                collect(toList());
    }

    default Map<String, Boolean> stopAllTasks(User user) {
        return getTasks().stream().
                filter(t -> user.equals(t.getUser())).
                filter(t -> t.getState() == Task.State.RUNNING || t.getState() == Task.State.QUEUED).collect(
                        toMap(t -> t.id, t -> stopTask(t.id)));
    }


    // for tests
    default String startTask(String taskName, User user, Map<String, Object> properties) throws IOException {
        return startTask(new Task<>(taskName, user, properties));
    }

    // TaskResource and pipeline tasks
    default String startTask(Class<?> taskClass, User user, Map<String, Object> properties) throws IOException {
        return startTask(new Task<>(taskClass.getName(), user, new Group(taskClass.getAnnotation(TaskGroup.class).value()), properties));
    }

    // for tests
    default String startTask(String taskName, User user, Group group, Map<String, Object> properties) throws IOException {
        return startTask(new Task<>(taskName, user, group, properties));
    }

    // BatchSearchResource and WebApp for batch searches
    default  String startTask(String id, Class<?> taskClass, User user) throws IOException {
        return startTask(new Task<>(id, taskClass.getName(), user, new Group(taskClass.getAnnotation(TaskGroup.class).value())));
    }

    /**
     * Main start task function. it saves the task in the persistent storage. If the task is new it will enqueue
     * it in the memory/redis/AMQP queue and return the id. Else it will not enqueue the task and return null.
     *
     * @param taskView: the task description.
     * @return task id if it was new and has been saved else null
     * @throws IOException in case of communication failure with Redis or AMQP broker
     */
    default <V> String startTask(Task<V> taskView) throws IOException {
        boolean saved = save(taskView);
        if (saved) {
            taskView.queue();
            enqueue(taskView);
        }
        return saved ? taskView.id: null;
    }

    default <V extends Serializable> Task<V> setResult(ResultEvent<V> e) {
        Task<V> taskView = getTask(e.taskId);
        if (taskView != null) {
            logger.info("result event for {}", e.taskId);
            taskView.setResult(e.result);
            save(taskView);
        } else {
            logger.warn("no task found for result event {}", e.taskId);
        }
        return taskView;
    }

    default <V extends Serializable> Task<V> setError(ErrorEvent e) {
        Task<V> taskView = getTask(e.taskId);
        if (taskView != null) {
            logger.info("error event for {}", e.taskId);
            taskView.setError(e.error);
            save(taskView);
        } else {
            logger.warn("no task found for error event {}", e.taskId);
        }
        return taskView;
    }

    default Task<?> setCanceled(CancelledEvent e) {
        Task<?> taskView = getTask(e.taskId);
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

    default Task<?> setProgress(ProgressEvent e) {
        logger.debug("progress event for {}", e.taskId);
        Task<?> taskView = getTask(e.taskId);
        if (taskView != null) {
            taskView.setProgress(e.progress);
            save(taskView);
        }
        return taskView;
    }

    default <V extends Serializable> Task<?> handleAck(TaskEvent e) {
        if (e instanceof CancelledEvent) {
            return setCanceled((CancelledEvent) e);
        }
        if (e instanceof ResultEvent) {
            return setResult(((ResultEvent<V>) e));
        }
        if (e instanceof ErrorEvent) {
            return setError((ErrorEvent) e);
        }
        if (e instanceof ProgressEvent) {
            return setProgress((ProgressEvent)e);
        }
        logger.warn("received event not handled {}", e);
        return null;
    }

    default void foo(String taskId, StateLatch stateLatch) {
        getTask(taskId).setLatch(stateLatch);
    }

}
