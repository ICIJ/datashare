package org.icij.datashare.asynctasks;

import org.icij.datashare.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;
import static org.icij.datashare.asynctasks.Task.State.NON_FINAL_STATES;

/**
 * Task manager interface whether implemented in-house or externally (proxy to an external TM)
 */
public interface TaskManager extends Closeable {
    Logger logger = LoggerFactory.getLogger(TaskManager.class);
    <V extends Serializable> String startTask(Task<V> taskView, Group group) throws IOException, TaskAlreadyExists;
    <V extends Serializable> Task<V> getTask(String taskId) throws IOException, UnknownTask;
    <V extends Serializable> Task<V> clearTask(String taskId) throws IOException, UnknownTask;
    boolean stopTask(String taskId) throws IOException, UnknownTask;
    Stream<Task<?>> getTasks(TaskFilters filters) throws IOException;
    // clearDoneTasks keeps a List return type otherwise tasks are cleared unless the stream is consumed
    List<Task<?>> clearDoneTasks(TaskFilters filter) throws IOException;
    boolean shutdown() throws IOException;
    void clear() throws IOException;
    boolean getHealth() throws IOException;
    int getTerminationPollingInterval();

    default Stream<Task<?>> getTasks() throws IOException {
        return getTasks(TaskFilters.empty());
    }

    default Map<String, Boolean> stopTasks(User user) throws IOException {
        return stopTasks(TaskFilters.empty().withUser(user));
    }

    default Map<String, Boolean> stopTasks(TaskFilters filters) throws IOException {
        TaskFilters filterNotCompleted = filters.withStates(NON_FINAL_STATES);
        Stream<Task<?>> taskStream = getTasks(filterNotCompleted);
        return taskStream.collect(toMap(t -> t.id, t -> {
            try {
                return stopTask(t.id);
            } catch (IOException | UnknownTask e) {
                logger.error("cannot stop task {}", t.id, e);
                return false;
            }
        }));
    }

    default List<Task<?>> clearDoneTasks() throws IOException {
        return clearDoneTasks(TaskFilters.empty());
    }

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

    default <V extends Serializable> String startTask(Task<V> taskView) throws IOException, TaskAlreadyExists {
        return startTask(taskView, null);
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
        List<Task<?>> unfinishedTasks = getTasks().filter(t -> !t.isFinished()).toList();
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

    default boolean awaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException, IOException {
        return !waitTasksToBeDone(timeout, timeUnit).isEmpty();
    }

    // for tests
    default void setLatch(String taskId, StateLatch stateLatch) throws IOException, UnknownTask {
        getTask(taskId).setLatch(stateLatch);
    }
}
