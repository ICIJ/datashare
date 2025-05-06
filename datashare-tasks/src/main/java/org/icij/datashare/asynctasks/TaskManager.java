package org.icij.datashare.asynctasks;

import java.util.HashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.icij.datashare.Entity;
import org.icij.datashare.asynctasks.bus.amqp.CancelledEvent;
import org.icij.datashare.asynctasks.bus.amqp.ErrorEvent;
import org.icij.datashare.asynctasks.bus.amqp.ProgressEvent;
import org.icij.datashare.asynctasks.bus.amqp.ResultEvent;
import org.icij.datashare.asynctasks.bus.amqp.TaskEvent;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.batch.WebQueryPagination;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toMap;
import static org.icij.datashare.LambdaExceptionUtils.rethrowFunction;
import static org.icij.datashare.text.StringUtils.getValue;

/**
 * Task manager interface with default methods common for all managers implementations.
 */
public interface TaskManager extends Closeable {
    int POLLING_INTERVAL = 5000;
    Logger logger = LoggerFactory.getLogger(TaskManager.class);
    Task getTask(String taskId) throws IOException, UnknownTask;
    Task clearTask(String taskId) throws IOException, UnknownTask;
    /**
     * Stops a task

     * @param taskId
     * @return true if the task was stopped, false if it was already stopped
     * @throws IOException if the task list cannot be retrieved because of a network failure
     * @throws UnknownTask if the task is unknown
     */
    boolean stopTask(String taskId) throws IOException, UnknownTask;

    Stream<Task> getTasks() throws IOException;
    List<Task> clearDoneTasks(Map<String, Pattern> filters) throws IOException;
    Group getTaskGroup(String taskId);
    boolean shutdown() throws IOException;

    void clear() throws IOException;

    boolean getHealth() throws IOException;

    int getTerminationPollingInterval();

    default Stream<Task> getTasks(User user) throws IOException {
        return getTasks(user, new HashMap<>(), new WebQueryPagination());
    }

    default Stream<Task> getTasks(User user, List<BatchSearchRecord> batchSearchRecords) throws IOException {
        // Filter the task to only get the one launched by the current user
        Stream<Task> userTasks = getTasks().filter(t -> user.equals(t.getUser()));
        // Convert the received batch search records to "proxy tasks"
        Stream<Task> batchSearchTasks = batchSearchRecords.stream().map(TaskManager::taskify);
        // Merge the to list of tasks and deduplicate them by id
        return Stream.concat(userTasks, batchSearchTasks)
                .collect(Collectors.toMap(
                        // We deduplicate tasks by id
                        Entity::getId,
                        task -> task,
                        // Get the first in priority
                        (first, second) -> first,
                        LinkedHashMap::new
                ))
                .values().stream();
    }

    default Stream<Task> getTasks(User user, Map<String, Pattern> filters) throws IOException {
        return getTasks(user, filters, new WebQueryPagination());
    }

    default Stream<Task> getTasks(User user, Map<String, Pattern> filters, WebQueryPagination pagination) throws IOException {
        Stream<Task> taskStream = getTasks().sorted(new Task.Comparator(pagination.sort, pagination.order));
        return getFilteredTaskStream(filters, taskStream)
            .filter(t -> user.equals(t.getUser()))
            .skip(pagination.from)
            .limit(pagination.size);
    }

    default Stream<Task> getTasks(User user, Map<String, Pattern> filters, WebQueryPagination pagination, List<BatchSearchRecord> batchSearchRecords) throws IOException {
        // Sort/order the tasks together
        Stream<Task> allTasks = getTasks(user, batchSearchRecords).sorted(new Task.Comparator(pagination.sort, pagination.order));
        // Finally, filter then paginate the tasks
        return getFilteredTaskStream(filters, allTasks).skip(pagination.from).limit(pagination.size);
    }

    default boolean awaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException, IOException {
        return !waitTasksToBeDone(timeout, timeUnit).isEmpty();
    }

    default Map<String, Boolean> stopTasks(User user) throws IOException {
        return stopTasks(user, new HashMap<>());
    }

    default Map<String, Boolean> stopTasks(User user, Map<String, Pattern> filters) throws IOException, UnknownTask {
        return getFilteredTaskStream(filters, getTasks())
            .filter(t -> user.equals(t.getUser()))
            .filter(t -> !t.getState().isFinal())
            .collect(toMap(t -> t.id, rethrowFunction(t -> stopTask(t.id))));
    }

    default List<Task> clearDoneTasks() throws IOException {
        return clearDoneTasks(new HashMap<>());
    }

    default Stream<Task> getFilteredTaskStream(Map<String, Pattern> filters, Stream<Task> taskStream) {
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
     void insert(Task task, Group group) throws IOException, TaskAlreadyExists;
     void update(Task task) throws IOException, UnknownTask;
     void saveResult(String taskId, byte[] result) throws IOException, UnknownTask;
     byte[] getResult(String taskId) throws IOException, UnknownTask;

    /**
     * This is a "inner method" that is used in the template method for start(task).
     * It put the task in the task queue for workers.
     * @param task task to be queued
     * @throws IOException if a network error occurs
     */
     void enqueue(Task task) throws IOException;

    // TaskResource and pipeline tasks
    default String startTask(Class<?> taskClass, User user, Map<String, Object> properties) throws IOException {
        return startTask(new Task(taskClass.getName(), user, properties), new Group(taskClass.getAnnotation(TaskGroup.class).value()));
    }

    // BatchSearchResource and WebApp for batch searches
    default String startTask(String uuid, Class<?> taskClass, User user, Map<String, Object> properties) throws IOException, TaskAlreadyExists {
        return startTask(new Task(uuid, taskClass.getName(), user, properties), new Group(taskClass.getAnnotation(TaskGroup.class).value()));
    }

    // for tests
    default String startTask(String taskName, User user, Map<String, Object> properties) throws IOException {
        return startTask(new Task(taskName, user, properties), new Group(TaskGroupType.Java));
    }
    // for tests
    default String startTask(String taskName, User user, Group group, Map<String, Object> properties) throws IOException {
        return startTask(new Task(taskName, user, properties), group);
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
    default String startTask(Task taskView, Group group) throws IOException, TaskAlreadyExists {
        insert(taskView, group);
        taskView.queue();
        enqueue(taskView);
        return taskView.id;
    }

    default String startTask(Task taskView) throws IOException, TaskAlreadyExists {
        return startTask(taskView, null);
    }

    default Task saveResult(ResultEvent e) throws IOException, UnknownTask {
        Task taskView = getTask(e.taskId);
        if (taskView != null) {
            logger.info("result event for {}", e.taskId);
            saveResult(e.taskId, e.result);
            taskView.setDone();
            update(taskView);
        } else {
            logger.warn("no task found for result event {}", e.taskId);
        }
        return taskView;
    }

    default Task setError(ErrorEvent e) throws IOException, UnknownTask {
        Task taskView = getTask(e.taskId);
        if (taskView != null) {
            logger.info("error event for {}", e.taskId);
            taskView.setError(e.error);
            update(taskView);
        } else {
            logger.warn("no task found for error event {}", e.taskId);
        }
        return taskView;
    }

    default Task setCanceled(CancelledEvent e) throws IOException, UnknownTask {
        Task taskView = getTask(e.taskId);
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

    default Task setProgress(ProgressEvent e) throws IOException, UnknownTask {
        logger.debug("progress event for {}", e.taskId);
        Task taskView = getTask(e.taskId);
        if (taskView != null) {
            taskView.setProgress(e.progress);
            update(taskView);
        }
        return taskView;
    }

    default Task handleAck(TaskEvent e) {
        try {
            if (e instanceof CancelledEvent ce) {
                return setCanceled(ce);
            }
            if (e instanceof ResultEvent) {
                return saveResult((ResultEvent) e);
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
    default List<Task> waitTasksToBeDone(int timeout, TimeUnit timeUnit) throws IOException {
        List<Task> unfinishedTasks = getTasks().filter(t -> !t.isFinished()).toList();
        long startTime = System.currentTimeMillis();
        while (true) {
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed > timeUnit.toMillis(timeout) || unfinishedTasks.isEmpty()) {
                break;
            }
            unfinishedTasks = getTasks().filter(t -> !t.isFinished()).toList();
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

    static Task taskify(BatchSearchRecord batchSearchRecord) {
        String name = "org.icij.datashare.tasks.BatchSearchRunnerProxy";
        Map<String, Object> batchRecord = Map.of("batchRecord", batchSearchRecord);
        Task task = new Task(batchSearchRecord.uuid, name, batchSearchRecord.user, batchRecord);
        // Build a state map between task and batch search record
        Map<BatchSearchRecord.State, Task.State> stateMap = new EnumMap<>(BatchSearchRecord.State.class);
        stateMap.put(BatchSearchRecord.State.QUEUED, Task.State.QUEUED);
        stateMap.put(BatchSearchRecord.State.RUNNING, Task.State.RUNNING);
        stateMap.put(BatchSearchRecord.State.SUCCESS, Task.State.DONE);
        stateMap.put(BatchSearchRecord.State.FAILURE, Task.State.ERROR);
        // Set the task state to the same state as the batch search record
        task.setState(stateMap.get(batchSearchRecord.state));
        // Set the correct creation date
        task.setCreatedAt(batchSearchRecord.date);
        return task;
    }
}
