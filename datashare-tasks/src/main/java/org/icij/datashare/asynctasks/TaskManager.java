package org.icij.datashare.asynctasks;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import org.icij.datashare.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
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
    int POLLING_INTERVAL = 5000;

    Logger logger = LoggerFactory.getLogger(TaskManager.class);
    <V extends Serializable> String     startTask(Task<V> taskView, Group group) throws IOException, TaskAlreadyExists;

    <V extends Serializable> Task<V> getTask(String taskId) throws IOException, UnknownTask;
    <V extends Serializable> Task<V> clearTask(String taskId) throws IOException, UnknownTask;

    boolean stopTask(String taskId) throws IOException, UnknownTask;

    // Task search for the frontend
    Stream<Task<?>> getTasks(TaskFilters filters) throws IOException;
    default Stream<Task<?>> getTasks() throws IOException {
        return getTasks(TaskFilters.empty());
    }

    // Fast and internal task state search for internal operations
    Stream<String> getTaskIds(TaskFilters filters) throws IOException;
    default Stream<String> getTaskIds() throws IOException {
        return getTaskIds(TaskFilters.empty());
    }

    // clearDoneTasks keeps a List return type otherwise tasks are cleared unless the stream is consumed
    List<Task<?>> clearDoneTasks(TaskFilters filter) throws IOException;
    boolean shutdown() throws IOException;
    // TODO: make this one async
    void clear() throws IOException;
    boolean getHealth() throws IOException;
    int getTerminationPollingInterval();

    default boolean awaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException, IOException {
        return waitTasksToBeDone(timeout, timeUnit) == 0L;
    }

    default Map<String, Boolean> stopTasks(User user) throws IOException {
        return stopTasks(TaskFilters.empty().withUser(user));
    }

    default Map<String, Boolean> stopTasks(TaskFilters filters) throws IOException {
        Set<Task.State> states = new HashSet<>(NON_FINAL_STATES);
        if (filters.hasStates()) {
            states.retainAll(filters.getStates());
        }
        TaskFilters filterNotCompleted = filters.withStates(states);
        return getTaskIds(filterNotCompleted).collect(toMap(Function.identity(), taskId -> {
            try {
                return stopTask(taskId);
            } catch (IOException | UnknownTask e) {
                logger.error("cannot stop task {}", taskId, e);
                return false;
            }
        }));
    }

    // TODO: make this one async
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
     * @return the number of unfinished tasks
     * @throws IOException if the task list cannot be retrieved because of a network failure.
     */
    default long waitTasksToBeDone(int timeout, TimeUnit timeUnit) throws IOException {
        return pollUntilDone(timeout, timeUnit, null);
    }

    /**
     * Same as {@link #waitTasksToBeDone(int, TimeUnit)} but only waits on the given
     * task ids instead of every non-final task in the (possibly shared and persistent)
     * repository. This lets a caller that owns a known set of tasks (e.g. a CLI run)
     * return as soon as its own tasks are done, without being blocked forever by
     * unrelated non-final rows left over from other or interrupted runs.
     *
     * @param taskIds the ids to wait on. An empty or null collection returns immediately.
     * @param timeout amount for the timeout
     * @param timeUnit unit of the timeout
     * @return the number of the given tasks still unfinished
     * @throws IOException if the task list cannot be retrieved because of a network failure.
     */
    default long waitTasksToBeDone(Collection<String> taskIds, int timeout, TimeUnit timeUnit) throws IOException {
        if (taskIds == null || taskIds.isEmpty()) {
            return 0L;
        }
        return pollUntilDone(timeout, timeUnit, new HashSet<>(taskIds));
    }

    private long pollUntilDone(int timeout, TimeUnit timeUnit, Set<String> scope) throws IOException {
        long startTime = System.currentTimeMillis();
        long maxDuration = timeUnit.toMillis(timeout);
        TaskFilters filterNotCompleted = TaskFilters.empty().withStates(NON_FINAL_STATES);
        long nUnfinished = countUnfinished(filterNotCompleted, scope);
        while (System.currentTimeMillis() - startTime < maxDuration && nUnfinished > 0) {
            try {
                Thread.sleep(Math.min(getTerminationPollingInterval(), maxDuration));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            nUnfinished = countUnfinished(filterNotCompleted, scope);
        }
        return nUnfinished;
    }

    private long countUnfinished(TaskFilters filterNotCompleted, Set<String> scope) throws IOException {
        Stream<String> taskIds = getTaskIds(filterNotCompleted);
        return (scope == null ? taskIds : taskIds.filter(scope::contains)).count();
    }

    /**
     * Mark as CANCELLED the non-final tasks matching {@code filters}, persisting the
     * transition to the backing repository, and return the ids that were reconciled.
     *
     * <p>Managers that delegate reconciliation to their backing engine (e.g. Temporal)
     * leave this a no-op. Callers must only pass filters that cannot match a live
     * worker's tasks: this is meant for cleaning up rows orphaned by dead runs, not
     * for stopping running work (see {@link #stopTasks(TaskFilters)} for that).
     */
    default List<String> reconcileStaleTasks(TaskFilters filters) throws IOException {
        return List.of();
    }

    // for tests
    default void setLatch(String taskId, StateLatch stateLatch) throws IOException, UnknownTask {
        getTask(taskId).setLatch(stateLatch);
    }
}
