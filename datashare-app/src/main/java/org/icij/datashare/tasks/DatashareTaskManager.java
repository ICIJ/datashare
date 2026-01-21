package org.icij.datashare.tasks;

import static java.util.stream.Collectors.toMap;

import java.io.IOException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.icij.datashare.Entity;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskFilters;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.TaskResult;
import org.icij.datashare.batch.BatchSearchRecord;

public interface DatashareTaskManager extends TaskManager {
    default Stream<Task<?>> getTasks(TaskFilters filters, Stream<BatchSearchRecord> batchSearchRecords) throws
        IOException {
        Stream<Task<?>> userTasks = getTasks(filters);
        // Remove any filter on task's user. This allows to display batch search records from other users.
        TaskFilters filtersWithoutUser = filters.withUser(null);
        // The list of batch search records must be converted to a list of task which allow us to apply the same task filters.
        Stream<Task<Integer>> batchSearchTasks =
            batchSearchRecords.map(DatashareTaskManager::taskify).filter(filtersWithoutUser::filter);
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

    static Task<Integer> taskify(BatchSearchRecord batchSearchRecord) {
        String name = "org.icij.datashare.tasks.BatchSearchRunnerProxy";
        Map<String, Object> batchRecord = Map.of("batchRecord", batchSearchRecord);
        Task<Integer> task =
            new Task<>(batchSearchRecord.uuid, name, batchSearchRecord.date, batchSearchRecord.user, batchRecord);
        // Build a state map between task and batch search record
        Map<BatchSearchRecord.State, Task.State> stateMap = new EnumMap<>(BatchSearchRecord.State.class);
        stateMap.put(BatchSearchRecord.State.QUEUED, Task.State.QUEUED);
        stateMap.put(BatchSearchRecord.State.RUNNING, Task.State.RUNNING);
        stateMap.put(BatchSearchRecord.State.SUCCESS, Task.State.DONE);
        stateMap.put(BatchSearchRecord.State.FAILURE, Task.State.ERROR);
        // Set the task state to the same state as the batch search record
        task.setState(stateMap.get(batchSearchRecord.state));
        // Set the task result
        TaskResult<Integer> result = new TaskResult<>(batchSearchRecord.nbResults);
        task.setResult(result);
        return task;
    }
}
