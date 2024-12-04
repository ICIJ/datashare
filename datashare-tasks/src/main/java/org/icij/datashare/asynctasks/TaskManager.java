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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

/**
 * Task manager interface with default methods common for all managers implementations.
 */
public interface TaskManager extends Closeable {
    Logger logger = LoggerFactory.getLogger(TaskManager.class);

    <V> Task<V> getTask(String taskId) throws IOException;
    <V> Task<V> clearTask(String taskId) throws IOException;
    boolean stopTask(String taskId) throws IOException;

    List<Task<?>> getTasks() throws IOException;
    List<Task<?>> getTasks(User user, Pattern pattern) throws IOException;
    List<Task<?>> clearDoneTasks() throws IOException;

    boolean shutdown() throws IOException;
    void clear() throws IOException;

    default boolean awaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException, IOException {
        return !waitTasksToBeDone(timeout, timeUnit).isEmpty();
    }

    default Map<String, Boolean> stopAllTasks(User user) throws IOException {
        return getTasks().stream().
                filter(t -> user.equals(t.getUser())).
                filter(t -> t.getState() == Task.State.RUNNING || t.getState() == Task.State.QUEUED).collect(
                        toMap(t -> t.id, t -> {
                            try {
                                return stopTask(t.id);
                            } catch (IOException e) {
                                logger.error("cannot stop task {}", t.id, e);
                                return false;
                            }
                        }));
    }

    /**
     * This is a "inner method" that is used in the template method for start(task).
     * It saves the method in the inner persistent state of TaskManagers implementations.
     *
     * @param task to be saved in persistent state
     * @return true if task has been saved
     * @throws IOException if a network error occurs
     */
    <V> boolean save(Task<V> task) throws IOException;

    /**
     * This is a "inner method" that is used in the template method for start(task).
     * It put the task in the task queue for workers.
     * @param task task to be queued
     * @throws IOException if a network error occurs
     */
    <V> void enqueue(Task<V> task) throws IOException;

    // TaskResource and pipeline tasks
    default String startTask(Class<?> taskClass, User user, Map<String, Object> properties) throws IOException {
        return startTask(new Task<>(taskClass.getName(), user, new Group(taskClass.getAnnotation(TaskGroup.class).value()), properties));
    }

    // BatchSearchResource and WebApp for batch searches
    default  String startTask(String id, Class<?> taskClass, User user) throws IOException {
        return startTask(new Task<>(id, taskClass.getName(), user, new Group(taskClass.getAnnotation(TaskGroup.class).value())));
    }

    // for tests
    default String startTask(String taskName, User user, Map<String, Object> properties) throws IOException {
        return startTask(new Task<>(taskName, user, properties));
    }
    // for tests
    default String startTask(String taskName, User user, Group group, Map<String, Object> properties) throws IOException {
        return startTask(new Task<>(taskName, user, group, properties));
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
            return taskView.id;
        }
        return null;
    }

    default <V extends Serializable> Task<V> setResult(ResultEvent<V> e) throws IOException {
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

    default <V extends Serializable> Task<V> setError(ErrorEvent e) throws IOException {
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

    default <V> Task<V> setCanceled(CancelledEvent e) throws IOException {
        Task<V> taskView = getTask(e.taskId);
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

    default <V> Task<V> setProgress(ProgressEvent e) throws IOException {
        logger.debug("progress event for {}", e.taskId);
        Task<V> taskView = getTask(e.taskId);
        if (taskView != null) {
            taskView.setProgress(e.progress);
            save(taskView);
        }
        return taskView;
    }

    default <V extends Serializable> Task<V> handleAck(TaskEvent e)  {
        try {
            if (e instanceof CancelledEvent ce) {
                return setCanceled(ce);
            }
            if (e instanceof ResultEvent) {
                return setResult((ResultEvent<V>) e);
            }
            if (e instanceof ErrorEvent ee) {
                return setError(ee);
            }
            if (e instanceof ProgressEvent pe) {
                return setProgress(pe);
            }
            logger.warn("received event not handled {}", e);
            return null;
        } catch (IOException ioe) {
            throw new TaskEventHandlingException(ioe);
        }
    }

    /**
     * wait for all the tasks to have a result using the thread lock in each task.
     *
     * Note that the timeout provided is used for each task. So in some (very specific) case it could
     * lead for a total delay of the number of task times the provided timeout.
     *
     * @param timeout amount for the timeout
     * @param timeUnit unit of the timeout
     * @return the list of tasks
     * @throws IOException if the task list cannot be retrieved because of a network failure.
     */
    default List<Task<?>> waitTasksToBeDone(int timeout, TimeUnit timeUnit) throws IOException {
        return getTasks().stream().peek(taskView -> {
            try {
                taskView.getResult(timeout, timeUnit);
            } catch (InterruptedException e) {
                logger.error("getResult interrupted while waiting for result", e);
            }
        }).collect(toList());
    }

    // for tests
    default void setLatch(String taskId, StateLatch stateLatch) throws IOException {
        getTask(taskId).setLatch(stateLatch);
    }

    static List<Task<?>> getTasks(Stream<Task<?>> stream, User user, Pattern pattern) {
        return stream.
                filter(t -> user.equals(t.getUser())).
                filter(t -> pattern.matcher(t.name).matches()).
                collect(toList());
    }

    class TaskEventHandlingException extends RuntimeException {
        public TaskEventHandlingException(Exception cause) {
            super(cause);
        }
    }
}
