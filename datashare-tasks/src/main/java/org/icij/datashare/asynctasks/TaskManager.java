package org.icij.datashare.asynctasks;

import static java.util.stream.Collectors.toMap;

import org.icij.datashare.asynctasks.bus.amqp.CancelledEvent;
import org.icij.datashare.asynctasks.bus.amqp.ErrorEvent;
import org.icij.datashare.asynctasks.bus.amqp.ProgressEvent;
import org.icij.datashare.asynctasks.bus.amqp.ResultEvent;
import org.icij.datashare.asynctasks.bus.amqp.TaskEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;


public interface TaskManager extends Closeable {
    Logger logger = LoggerFactory.getLogger(TaskManager.class);

    boolean stopTask(String taskId) throws IOException;
    <V> Task<V> clearTask(String taskId) throws IOException;
    boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException, IOException;
    <V> Task<V> getTask(String taskId) throws IOException;
    List<Task<?>> getTasks() throws IOException;
    List<Task<?>> getTasks(Pattern pattern) throws IOException;
    Group getTaskGroup(String taskId);
    List<Task<?>> clearDoneTasks() throws IOException;
    void clear() throws IOException;
    <V> void saveMetadata(TaskMetadata<V> taskMetadata) throws IOException, TaskAlreadyExists;
    <V> void persistUpdate(Task<V> task) throws IOException, UnknownTask;
    void enqueue(Task<?> task) throws IOException;

    default List<Task<?>> getTasks(Stream<Task<?>> stream, Pattern pattern) {
        return stream
            .filter(t -> pattern.matcher(t.name).matches())
            .toList();
    }

    default Map<String, Boolean> stopAllTasks() throws IOException {
        return getTasks().stream()
            .filter(t -> t.getState() == Task.State.RUNNING || t.getState() == Task.State.QUEUED).collect(
                        toMap(t -> t.id, t -> {
                            try {
                                return stopTask(t.id);
                            } catch (IOException e) {
                                logger.error("cannot stop task {}", t.id, e);
                                return false;
                            }
                        }));
    }


    // for tests
    default String startTask(String taskName, Map<String, Object> properties) throws IOException {
        return startTask(new Task<>(taskName, properties), null);
    }

    // TaskResource and pipeline tasks
    default String startTask(Class<?> taskClass, Map<String, Object> properties) throws IOException {
        return startTask(new Task<>(taskClass.getName(), properties), new Group(taskClass.getAnnotation(TaskGroup.class).value()));
    }

    // for tests
    default String startTask(String taskName, Group group, Map<String, Object> properties) throws IOException {
        return startTask(new Task<>(taskName, properties), group);
    }

    // BatchSearchResource and WebApp for batch searches
    default  String startTask(String id, Class<?> taskClass) throws IOException {
        return startTask(new Task<>(id, taskClass.getName()), new Group(taskClass.getAnnotation(TaskGroup.class).value()));
    }

    /**
     * Main start task function. it saves the task in the persistent storage. If the task is new it will enqueue
     * it in the memory/redis/AMQP queue and return the id. Else it will not enqueue the task and return null.
     *
     * @param taskView: the task description.
     * @param group: task group
     * @return task id if it was new and has been saved else null
     * @throws IOException in case of communication failure with Redis or AMQP broker
     */
    default <V> String startTask(Task<V> taskView, Group group) throws IOException {
        try {
            save(taskView, group);
        } catch (TaskAlreadyExists ignored) {
            throw new RuntimeException("task with id " + taskView.id + " was already save !");
        }
        taskView.queue();
        enqueue(taskView);
        return taskView.id;
    }

    default <V> String startTask(Task<V> taskView) throws IOException {
        return startTask(taskView, null);
    }

    default void save(Task<?> task, Group group) throws IOException, TaskAlreadyExists {
        saveMetadata(new TaskMetadata<>(task, group));
    }

    default void update(Task<?> task) throws IOException {
        try {
            persistUpdate(task);
        } catch (UnknownTask e) {
            throw new RuntimeException("task " + task.id + " is unknown, save it first !");
        }
    }

    default <V extends Serializable> Task<V> setResult(ResultEvent<V> e) throws IOException {
        Task<V> taskView = getTask(e.taskId);
        if (taskView != null) {
            logger.info("result event for {}", e.taskId);
            taskView.setResult(e.result);
            update(taskView);
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
            update(taskView);
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
            update(taskView);
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
            update(taskView);
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

    // for tests
    default void setLatch(String taskId, StateLatch stateLatch) throws IOException {
        getTask(taskId).setLatch(stateLatch);
    }

    class TaskEventHandlingException extends RuntimeException {
        public TaskEventHandlingException(Exception cause) {
            super(cause);
        }
    }
}
