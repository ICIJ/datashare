package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.errors.ForbiddenException;
import org.icij.datashare.Entity;
import org.icij.datashare.asynctasks.*;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.session.ScopedUser;
import org.icij.datashare.text.ProjectProxy;
import org.icij.datashare.user.User;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.stream.Stream;
import static java.util.stream.Collectors.toMap;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;

@Singleton
public class TaskFinder {
    private final TaskManager taskManager;
    private final BatchSearchRepository batchSearchRepository;
    private static final Map<BatchSearchRecord.State, Task.State> STATE_MAP = new EnumMap<>(
            Map.of(BatchSearchRecord.State.QUEUED, Task.State.QUEUED, BatchSearchRecord.State.RUNNING,
                   Task.State.RUNNING, BatchSearchRecord.State.SUCCESS, Task.State.DONE,
                   BatchSearchRecord.State.FAILURE, Task.State.ERROR));

    @Inject
    public TaskFinder(TaskManager taskManager, BatchSearchRepository batchSearchRepository) {
        this.taskManager = taskManager;
        this.batchSearchRepository = batchSearchRepository;
    }

    /**
     * Retrieves all the tasks started by the user, combined with all the BatchSearchTasks
     * made on the projects to which the user belongs, filtered using {@param filters}.
     * User filter of {@param filters} are ignored.
     * BatchSearchTasks of the project are retrieve using {@link BatchSearchRecord} converted to Task
     * @param filters
     * @return
     * @throws IOException
     */
    public Stream<Task<?>> findVisibleTasksFor(User user, TaskFilters filters) throws IOException {
        if (user == null) {
            throw new IllegalArgumentException("Cannot retrieve Tasks of a null user");
        }
        filters = filters.with(user); //Ensure only the tasks of the user are returned
        Stream<Task<?>> userTasks = taskManager.getTasks(filters);
        // Use this filter to retrieve the BatchSearches of the project
        TaskFilters filtersWithoutUser = filters.with((User) null);
        List<BatchSearchRecord> batchSearchRecords = batchSearchRepository.getRecords(user, user.getProjectNames());

        Stream<Task<Integer>> batchSearchTasks =
                batchSearchRecords.stream().map(TaskFinder::taskify).filter(filtersWithoutUser::filter);
        // Merge the list of tasks and deduplicate them by id
        Stream<Task<?>> allTasks = Stream.concat(userTasks, batchSearchTasks);
        Stream<Task<?>> inScope = allTasks.filter(t -> user.scope().allowsAll(projectIdsOf(t)));
        return inScope.collect(toMap(
                // We deduplicate tasks by id
                Entity::getId, task -> task,
                // Get the first in priority
                (first, second) -> first, LinkedHashMap::new)).values().stream().map(t -> seenBy(user, t));

    }

    /**
     * Retrieves a task by id.
     * First looks in the task repository, then falls back to the user's batch search records,
     * converting the matching {@link BatchSearchRecord} to a {@link Task} if found.
     *
     * @param user the user requesting the task
     * @param id   the task id
     * @return the task, either from the task repository or converted from a batch search record
     * @throws UnknownTask if no task or batch search record matches the given id
     * @throws net.codestory.http.errors.ForbiddenException if a managed task exists but the user has no access to it
     * @throws IOException if an I/O error occurs while fetching batch search records
     */
    public Task<?> findVisibleTaskFor(User user, String id) throws IOException {
        Optional<Task<?>> managed = getTaskFromManager(id);
        Optional<Task<?>> owned = managed.filter(t -> Objects.equals(t.getUser(), user));
        Optional<Task<?>> inScope = owned.filter(t -> user.scope().allowsAll(projectIdsOf(t)));
        if (inScope.isPresent()) {
            return seenBy(user, managed.get());
        }
        List<BatchSearchRecord> records = batchSearchRepository.getRecords(user, user.getProjectNames());
        Optional<BatchSearchRecord> record = records.stream().filter(r -> r.uuid.equals(id)).findFirst();
        Optional<Task<?>> taskified = record.map(TaskFinder::taskify);
        Optional<Task<?>> inScopeRecord = taskified.filter(t -> user.scope().allowsAll(projectIdsOf(t)));
        Optional<Task<?>> visible = inScopeRecord.map(t -> seenBy(user, t));
        return visible.orElseThrow(() -> managed.isPresent() ? new ForbiddenException() : new UnknownTask(id));
    }

    public static List<String> projectIdsOf(Task<?> task) {
        Object batchSearchRecord = task.args.get("batchRecord");
        Object batchDownload = task.args.get("batchDownload");
        if (batchSearchRecord instanceof BatchSearchRecord bsr) {
            return bsr.projects.stream().map(ProjectProxy::getId).toList();
        }
        if (batchDownload instanceof BatchDownload bd) {
            return bd.projects.stream().map(ProjectProxy::getId).toList();
        }
        List<String> projectIds = new ArrayList<>();
        for (String key : List.of(DEFAULT_PROJECT_OPT, "project")) {
            if (task.args.get(key) instanceof String projectId) {
                projectIds.add(projectId);
            }
        }
        return projectIds;
    }

    // the stored owner keeps all its projects: the task needs them when it runs
    private static <V extends Serializable> Task<?> seenBy(User user, Task<V> task) {
        if (!(user instanceof ScopedUser scoped) || !Objects.equals(task.getUser(), user)) {
            return task;
        }
        DatashareUser narrowed = new DatashareUser(scoped.getDetails());
        Map<String, Object> args = new HashMap<>(task.args);
        args.put(Task.USER_KEY, narrowed);
        return new Task<>(task.id, task.name, task.getState(), task.getProgress(), task.createdAt,
                          task.getRetriesLeft(), task.getCompletedAt(), args, task.getResult(), task.getError());
    }

    private Optional<Task<?>> getTaskFromManager(String id) throws IOException {
        try {
            return Optional.of(taskManager.getTask(id));
        } catch (UnknownTask e) {
            return Optional.empty();
        }
    }

    private static Task<Integer> taskify(BatchSearchRecord batchSearchRecord) {
        String name = "org.icij.datashare.tasks.BatchSearchRunnerProxy";
        Map<String, Object> batchRecord = Map.of("batchRecord", batchSearchRecord);
        Task<Integer> task =
                new Task<>(batchSearchRecord.uuid, name, batchSearchRecord.date, batchSearchRecord.user, batchRecord);
        // Build a state map between task and batch search record

        // Set the task state to the same state as the batch search record
        task.setState(STATE_MAP.get(batchSearchRecord.state));
        // Set the task result
        TaskResult<Integer> result = new TaskResult<>(batchSearchRecord.nbResults);
        task.setResult(result);
        return task;
    }
}
