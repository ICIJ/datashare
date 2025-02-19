package org.icij.datashare.asynctasks;

import java.io.IOException;
import java.io.Serializable;
import java.util.Map;
import java.util.Optional;

public interface TaskRepository extends Map<String, TaskMetadata<?>> {
    default <V extends Serializable> void insert(Task<V> task, Group group) throws IOException, TaskAlreadyExists {
        if (containsKey(task.id)) {
            throw new TaskAlreadyExists(task.id);
        }
        put(task.id, new TaskMetadata<>(task, group));
    }

    default  <V extends Serializable> void update(Task<V> task) throws IOException, UnknownTask {
        put(task.id, Optional.ofNullable(get(task.id)).orElseThrow(() -> new UnknownTask(task.id)).withTask(task));
    }

    default  <V extends Serializable> Task<V> getTask(String taskId) throws UnknownTask {
        return (Task<V>) Optional.ofNullable(get(taskId)).orElseThrow(() -> new UnknownTask(taskId)).task();
    }

    default boolean isEmpty() {
        return size() == 0;
    }
}
