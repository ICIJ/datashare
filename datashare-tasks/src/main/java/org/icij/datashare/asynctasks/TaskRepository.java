package org.icij.datashare.asynctasks;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

public interface TaskRepository extends Map<String, TaskMetadata> {
    default void insert(Task task, Group group) throws IOException, TaskAlreadyExists {
        if (containsKey(task.id)) {
            throw new TaskAlreadyExists(task.id);
        }
        put(task.id, new TaskMetadata(task, group));
    }

    default void update(Task task) throws IOException, UnknownTask {
        put(task.id, Optional.ofNullable(get(task.id)).orElseThrow(() -> new UnknownTask(task.id)).withTask(task));
    }

    default Task getTask(String taskId) throws UnknownTask {
        return Optional.ofNullable(get(taskId)).orElseThrow(() -> new UnknownTask(taskId)).task();
    }

    void saveResult(String taskId, byte[] result) throws UnknownTask;

    byte[] getResult(String taskId) throws UnknownTask;

    default boolean isEmpty() {
        return size() == 0;
    }
}
