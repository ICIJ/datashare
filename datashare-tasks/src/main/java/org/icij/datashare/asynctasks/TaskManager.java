package org.icij.datashare.asynctasks;

import org.icij.datashare.asynctasks.bus.amqp.*;
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
    <V extends Serializable> Task<V> getTask(String taskId) throws IOException;
    <V extends Serializable> Task<V> clearTask(String taskId) throws IOException;
    boolean stopTask(String taskId) throws IOException;

    List<Task<?>> getTasks() throws IOException;
    List<Task<?>> clearDoneTasks() throws IOException;
    boolean shutdown() throws IOException;

    void clear() throws IOException;

    boolean getHealth() throws IOException;

    default List<Task<?>> getTasks(User user) throws IOException {
        return getTasks(user, new HashMap<>(), new WebQueryPagination());
    }
    default List<Task<?>> getTasks(User user, Map<String, Pattern> filters) throws IOException {
        return getTasks(user, filters, new WebQueryPagination());
    }

    default List<Task<?>> getTasks(User user, Map<String, Pattern> filters, WebQueryPagination pagination) throws IOException {
        Stream<Task<?>> taskStream = getTasks().stream().sorted(new Task.Comparator(pagination.sort, pagination.order));
        for (Map.Entry<String, Pattern> filter : filters.entrySet()) {
            taskStream = taskStream.filter(task -> {
                Map<String, Object> objectMap = JsonObjectMapper.getJson(task);
                return filter.getValue().matcher(String.valueOf(getValue(objectMap, filter.getKey()))).matches();
            });
        }
        return taskStream.filter(t -> user.equals(t.getUser())).skip(pagination.from).limit(pagination.size).collect(toList());
    }

    default int getTerminationPollingInterval() {return POLLING_INTERVAL;}
    default boolean awaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException, IOException {
        return !waitTasksToBeDone(timeout, timeUnit).isEmpty();
    }

    default Map<String, Boolean> stopAllTasks(User user) throws IOException {
        return getTasks().stream().
                filter(t -> user.equals(t.getUser())).
                filter(t -> t.getState() == Task.State.RUNNING || t.getState() == Task.State.QUEUED || t.getState() == Task.State.CREATED).collect(
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
    <V extends Serializable> boolean save(Task<V> task) throws IOException;

    /**
     * This is a "inner method" that is used in the template method for start(task).
     * It put the task in the task queue for workers.
     * @param task task to be queued
     * @throws IOException if a network error occurs
     */
    <V extends Serializable> void enqueue(Task<V> task) throws IOException;

    // TaskResource and pipeline tasks
    default String startTask(Class<?> taskClass, User user, Map<String, Object> properties) throws IOException {
        return startTask(new Task<>(taskClass.getName(), user, new Group(taskClass.getAnnotation(TaskGroup.class).value()), properties));
    }

    // BatchSearchResource and WebApp for batch searches
    default String startTask(String uuid, Class<?> taskClass, User user, Map<String, Object> properties) throws IOException {
        return startTask(new Task<>(uuid, taskClass.getName(), user, new Group(taskClass.getAnnotation(TaskGroup.class).value()), properties));
    }

    // for tests
    default String startTask(String taskName, User user, Map<String, Object> properties) throws IOException {
        return startTask(new Task<>(taskName, user, new Group(TaskGroupType.Java), properties));
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
    default <V extends Serializable> String startTask(Task<V> taskView) throws IOException {
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
            taskView.setResult(new TaskResult<>(e.result));
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

    default <V extends Serializable> Task<V> setCanceled(CancelledEvent e) throws IOException {
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

    default <V extends Serializable> Task<V> setProgress(ProgressEvent e) throws IOException {
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
     * wait for all the tasks to have a result.
     *
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
    default void setLatch(String taskId, StateLatch stateLatch) throws IOException {
        getTask(taskId).setLatch(stateLatch);
    }

    class TaskEventHandlingException extends RuntimeException {
        public TaskEventHandlingException(Exception cause) {
            super(cause);
        }
    }
}
