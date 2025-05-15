package org.icij.datashare.asynctasks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.icij.datashare.Entity;
import org.icij.datashare.asynctasks.bus.amqp.CancelledEvent;
import org.icij.datashare.asynctasks.bus.amqp.ErrorEvent;
import org.icij.datashare.asynctasks.bus.amqp.ProgressEvent;
import org.icij.datashare.asynctasks.bus.amqp.ResultEvent;
import org.icij.datashare.asynctasks.bus.amqp.TaskEvent;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;
import static org.icij.datashare.LambdaExceptionUtils.rethrowFunction;
import static org.icij.datashare.asynctasks.Task.State.NON_FINAL_STATES;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;

/**
 * Task manager interface with default methods common for all managers implementations.
 */
public interface TaskManager extends Closeable {
    int POLLING_INTERVAL = 5000;
    Logger logger = LoggerFactory.getLogger(TaskManager.class);
    Task getTask(String taskId) throws IOException, UnknownTask;
    Task clearTask(String taskId) throws IOException, UnknownTask;
    boolean stopTask(String taskId) throws IOException, UnknownTask;

    Stream<Task> getTasks(TaskFilters filters) throws IOException;
    // clearDoneTasks keeps a List return type otherwise tasks are cleared unless the stream is consumed
    List<Task> clearDoneTasks(TaskFilters filter) throws IOException;
    Group getTaskGroup(String taskId) throws IOException, UnknownTask;
    boolean shutdown() throws IOException;

    void clear() throws IOException;

    boolean getHealth() throws IOException;

    int getTerminationPollingInterval();

    default Stream<Task> getTasks() throws IOException {
        return getTasks(TaskFilters.empty());
    }

    static ObjectMapper getDefaultObjectMapper() {
        return MAPPER;
    }

    default Stream<Task> getTasks(TaskFilters filters, Stream<BatchSearchRecord> batchSearchRecords) throws IOException {
        Stream<Task> userTasks = getTasks(filters);
        // Remove any filter on task's user. This allows to display batch search records from other users.
        TaskFilters filtersWithoutUser = filters.withUser(null);
        // The list of batch search records must be converted to a list of task which allow us to apply the same task filters.
        Stream<Task> batchSearchTasks = batchSearchRecords.map(rethrowFunction(TaskManager::taskify))
            .filter(filtersWithoutUser::filter);
        // Merge the list of tasks and deduplicate them by id
        return Stream.concat(userTasks, batchSearchTasks)
            .collect(toMap(
                // We deduplicate tasks by id
                Entity::getId,
                task -> task,
                // Get the first in priority
                (first, second) -> first,
                LinkedHashMap::new
            ))
            .values()
                .stream()
                ;
    }

    default boolean awaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException, IOException {
        return !waitTasksToBeDone(timeout, timeUnit).isEmpty();
    }

    default Map<String, Boolean> stopTasks(User user) throws IOException {
        return stopTasks(TaskFilters.empty().withUser(user));
    }

    default Map<String, Boolean> stopTasks(TaskFilters filters) throws IOException {
        TaskFilters filterNotCompleted = filters.withStates(NON_FINAL_STATES);
        Stream<Task> taskStream = getTasks(filterNotCompleted);
        return taskStream.collect(toMap(t -> t.id, t -> {
            try {
                return stopTask(t.id);
            } catch (IOException | UnknownTask e) {
                logger.error("cannot stop task {}", t.id, e);
                return false;
            }
        }));
    }

    default List<Task> clearDoneTasks() throws IOException {
        return clearDoneTasks(TaskFilters.empty());
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

    default Task setResult(ResultEvent e) throws IOException, UnknownTask {
        Task taskView = getTask(e.taskId);
        logger.info("result event for {}", e.taskId);
        taskView.setResult(e.result);
        update(taskView);
        return taskView;
    }

    default Task setError(ErrorEvent e) throws IOException, UnknownTask {
        Task taskView = getTask(e.taskId);
        logger.info("error event for {}", e.taskId);
        taskView.setError(e.error);
        update(taskView);
        return taskView;
    }

    default Task setCanceled(CancelledEvent e) throws IOException, UnknownTask {
        Task taskView = getTask(e.taskId);
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
        return taskView;
    }

    default Task setProgress(ProgressEvent e) throws IOException, UnknownTask {
        logger.debug("progress event for {}", e.taskId);
        Task taskView = getTask(e.taskId);
        taskView.setProgress(e.progress);
        update(taskView);
        return taskView;
    }

    default Task handleAck(TaskEvent e) {
        try {
            if (e instanceof CancelledEvent ce) {
                return setCanceled(ce);
            }
            if (e instanceof ResultEvent) {
                return setResult((ResultEvent) e);
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
        long startTime = System.currentTimeMillis();
        List<Task> unfinishedTasks = getTasks().filter(t -> !t.isFinished()).toList();
        while (System.currentTimeMillis() - startTime < timeUnit.toMillis(timeout) && !unfinishedTasks.isEmpty()) {
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

    static Task taskify(BatchSearchRecord batchSearchRecord) throws JsonProcessingException {
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
        // Set the task result
        byte[] result = getDefaultObjectMapper().writeValueAsBytes(batchSearchRecord.nbResults);
        task.setResult(result);
        // Set the correct creation date
        task.setCreatedAt(batchSearchRecord.date);
        return task;
    }
}
