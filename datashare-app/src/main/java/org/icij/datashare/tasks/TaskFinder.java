package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.icij.datashare.Entity;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskFilters;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.TaskResult;
import org.icij.datashare.asynctasks.UnknownTask;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.user.User;

import java.io.IOException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;

@Singleton
public class TaskFinder {

    private final TaskManager taskManager;
    private final BatchSearchRepository batchSearchRepository;

    private static final Map<BatchSearchRecord.State, Task.State> STATE_MAP = new EnumMap<>(Map.of(
            BatchSearchRecord.State.QUEUED,  Task.State.QUEUED,
            BatchSearchRecord.State.RUNNING, Task.State.RUNNING,
            BatchSearchRecord.State.SUCCESS, Task.State.DONE,
            BatchSearchRecord.State.FAILURE, Task.State.ERROR
    ));
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
    public Stream<Task<?>> findVisibleTasksFor(User user, TaskFilters filters) throws
            IOException {
        if(user == null) {
            throw new IllegalArgumentException("Cannot retrieve Tasks of a null user");
        }
        filters = filters.withUser(user); //Ensure only the tasks of the user are returned
        Stream<Task<?>> userTasks = taskManager.getTasks(filters);
        // Use this filter to retrieve the BatchSearches of the project
        TaskFilters filtersWithoutUser = filters.withUser(null);
        List<BatchSearchRecord> batchSearchRecords = batchSearchRepository.getRecords(user, user.getProjectNames());

        Stream<Task<Integer>> batchSearchTasks =
                batchSearchRecords.stream().map(TaskFinder::taskify).filter(filtersWithoutUser::filter);
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
                .map(t -> (Task<?>) t);

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
     * @throws IOException if an I/O error occurs while fetching batch search records
     */
    public Task<?> findVisibleTaskFor(User user, String id) throws IOException {
        try {
            return taskManager.getTask(id);
        } catch (UnknownTask e) {
            return batchSearchRepository.getRecords(user, user.getProjectNames())
                    .stream()
                    .filter(r -> r.uuid.equals(id))
                    .findFirst()
                    .map(TaskFinder::taskify)
                    .orElseThrow(() -> new UnknownTask(id));
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
