package org.icij.datashare.asynctasks;

import org.icij.datashare.asynctasks.bus.amqp.CancelledEvent;
import org.icij.datashare.asynctasks.bus.amqp.ErrorEvent;
import org.icij.datashare.asynctasks.bus.amqp.ProgressEvent;
import org.icij.datashare.asynctasks.bus.amqp.ResultEvent;
import org.icij.datashare.asynctasks.bus.amqp.TaskEvent;
import org.icij.datashare.batch.WebQueryPagination;
import org.icij.datashare.json.JsonObjectMapper;
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
import static org.icij.datashare.text.StringUtils.getValue;

/**
 * Task manager interface with default methods common for all managers implementations.
 */
public interface TaskManager extends Closeable {
    int POLLING_INTERVAL = 5000;
    Logger logger = LoggerFactory.getLogger(TaskManager.class);
    <V extends Serializable> Task<V> getTask(String taskId) throws IOException, UnknownTask;
    <V extends Serializable> Task<V> clearTask(String taskId) throws IOException, UnknownTask;
    boolean stopTask(String taskId) throws IOException, UnknownTask;

    List<Task<?>> getTasks() throws IOException;
    List<Task<?>> clearDoneTasks(Map<String, Pattern> filters) throws IOException;
    Group getTaskGroup(String taskId);
    boolean shutdown() throws IOException;

    void clear() throws IOException;

    boolean getHealth() throws IOException;

    int getTerminationPollingInterval();
    default List<Task<?>> getTasks(User user) throws IOException {
        return getTasks(user, new HashMap<>(), new WebQueryPagination());
    }

    default List<Task<?>> getTasks(User user, Map<String, Pattern> filters) throws IOException {
        return getTasks(user, filters, new WebQueryPagination());
    }

    default List<Task<?>> getTasks(User user, Map<String, Pattern> filters, WebQueryPagination pagination) throws IOException {
        Stream<Task<?>> taskStream = getTasks().stream().sorted(new Task.Comparator(pagination.sort, pagination.order));
        taskStream = getFilteredTaskStream(filters, taskStream);
        return taskStream.filter(t -> user.equals(t.getUser())).skip(pagination.from).limit(pagination.size).collect(toList());
    }
    default boolean awaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException, IOException {
        return !waitTasksToBeDone(timeout, timeUnit).isEmpty();
    }

    default Map<String, Boolean> stopTasks(User user) throws IOException {
        return stopTasks(user, new HashMap<>());
    }

    default Map<String, Boolean> stopTasks(User user, Map<String, Pattern> filters) throws IOException {
        Stream<Task<?>> taskStream = getTasks().stream();
        taskStream = getFilteredTaskStream(filters, taskStream);
        return taskStream.
                filter(t -> user.equals(t.getUser())).
                filter(t -> t.getState() == Task.State.RUNNING || t.getState() == Task.State.QUEUED || t.getState() == Task.State.CREATED).collect(
                        toMap(t -> t.id, t -> {
                            try {
                                return stopTask(t.id);
                            } catch (IOException | UnknownTask e) {
                                logger.error("cannot stop task {}", t.id, e);
                                return false;
                            }
                        }));
    }

    default List<Task<?>> clearDoneTasks() throws IOException {
        return clearDoneTasks(new HashMap<>());
    };

    default Stream<Task<?>> getFilteredTaskStream(Map<String, Pattern> filters, Stream<Task<?>> taskStream) {
        for (Map.Entry<String, Pattern> filter : filters.entrySet()) {
            taskStream = taskStream.filter(task -> {
                Map<String, Object> objectMap = JsonObjectMapper.getJson(task);
                return filter.getValue().matcher(String.valueOf(getValue(objectMap, filter.getKey()))).matches();
            });
        }
        return taskStream;
    }

    /**
     * This is a "inner method" that is used in the template method for start(task).
     * It saves the method in the inner persistent state of TaskManagers implementations.
     *
     * @param task to be saved in persistent state
     * @throws IOException if a network error occurs
     */
    <V extends Serializable> void insert(Task<V> task, Group group) throws IOException, TaskAlreadyExists;
    <V extends Serializable> void update(Task<V> task) throws IOException, UnknownTask;

    /**
     * This is a "inner method" that is used in the template method for start(task).
     * It put the task in the task queue for workers.
     * @param task task to be queued
     * @throws IOException if a network error occurs
     */
    <V extends Serializable> void enqueue(Task<V> task) throws IOException;

    // TaskResource and pipeline tasks
    default String startTask(Class<?> taskClass, User user, Map<String, Object> properties) throws IOException {
        return startTask(new Task<>(taskClass.getName(), user, properties), new Group(taskClass.getAnnotation(TaskGroup.class).value()));
    }

    // BatchSearchResource and WebApp for batch searches
    default String startTask(String uuid, Class<?> taskClass, User user, Map<String, Object> properties) throws IOException, TaskAlreadyExists {
        return startTask(new Task<>(uuid, taskClass.getName(), user, properties), new Group(taskClass.getAnnotation(TaskGroup.class).value()));
    }

    // for tests
    default String startTask(String taskName, User user, Map<String, Object> properties) throws IOException {
        return startTask(new Task<>(taskName, user, properties), new Group(TaskGroupType.Java));
    }
    // for tests
    default String startTask(String taskName, User user, Group group, Map<String, Object> properties) throws IOException {
        return startTask(new Task<>(taskName, user, properties), group);
    }

    /**
     * Main start task function. it saves the task in the persistent storage. If the task is new it will enqueue
     * it in the memory/redis/AMQP queue and return the id. Else it will not enqueue the task and return null.
     *
     * @param taskView: the task description.
     * @param group: task group
     * @return task id if it was new and has been saved else null
     * @throws IOException in case of communication failure with Redis or AMQP broker
     * @throws TaskAlreadyExists when the task has already been started
     */
    default <V extends Serializable> String startTask(Task<V> taskView, Group group) throws IOException, TaskAlreadyExists {
        insert(taskView, group);
        taskView.queue();
        enqueue(taskView);
        return taskView.id;
    }

    default <V extends Serializable> String startTask(Task<V> taskView) throws IOException, TaskAlreadyExists {
        return startTask(taskView, null);
    }

    default <V extends Serializable> Task<V> setResult(ResultEvent<V> e) throws IOException, UnknownTask {
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

    default <V extends Serializable> Task<V> setError(ErrorEvent e) throws IOException, UnknownTask {
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

    default <V extends Serializable> Task<V> setCanceled(CancelledEvent e) throws IOException, UnknownTask {
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

    default <V extends Serializable> Task<V> setProgress(ProgressEvent e) throws IOException, UnknownTask {
        logger.debug("progress event for {}", e.taskId);
        Task<V> taskView = getTask(e.taskId);
        if (taskView != null) {
            taskView.setProgress(e.progress);
            update(taskView);
        }
        return taskView;
    }

    default <V extends Serializable> Task<V> handleAck(TaskEvent e) {
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
        } catch (IOException | UnknownTask ioe) {
            throw new TaskEventHandlingException(ioe);
        }
    }

    /**
     * wait for all the tasks to have a result.
     * This method will poll the task list. So if there are a lot of tasks or if tasks are
     * containing a lot of information, this method call could be very intensive on network and CPU.
     *
     * @param timeout amount for the timeout
     * @param timeUnit unit of the timeout
     * @return the list of unfinished/alive tasks
     * @throws IOException if the task list cannot be retrieved because of a network failure.
     */
    default List<Task<?>> waitTasksToBeDone(int timeout, TimeUnit timeUnit) throws IOException {
        long startTime = System.currentTimeMillis();
        List<Task<?>> unfinishedTasks = getTasks().stream().filter(t -> !t.isFinished()).toList();
        while (System.currentTimeMillis() - startTime < timeUnit.toMillis(timeout) && !unfinishedTasks.isEmpty()) {
            unfinishedTasks = getTasks().stream().filter(t -> !t.isFinished()).toList();
            try {
                Thread.sleep(getTerminationPollingInterval());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return unfinishedTasks;
    }

    // for tests
    default void setLatch(String taskId, StateLatch stateLatch) throws IOException, UnknownTask {
        getTask(taskId).setLatch(stateLatch);
    }

    class TaskEventHandlingException extends RuntimeException {
        public TaskEventHandlingException(Exception cause) {
            super(cause);
        }
    }
}
