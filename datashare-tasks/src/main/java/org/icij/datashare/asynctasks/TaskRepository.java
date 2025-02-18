package org.icij.datashare.asynctasks;

import java.util.Map;
import java.util.Optional;

public interface TaskRepository extends Map<String, TaskMetadata<?>> {
    default void persist(Task<?> task, Group group) throws TaskAlreadyExists {
        if (containsKey(task.id)) {
            throw new TaskAlreadyExists(task.id);
        }
        TaskMetadata<?> taskMetadata = new TaskMetadata<>(task, group);
        put(taskMetadata.taskId(), taskMetadata);
    }

    default <V> Task<V> update(Task<V> task) {
        TaskMetadata<V> taskMetadata = (TaskMetadata<V>) get(task.getId());
        return (Task<V>) put(task.getId(), taskMetadata.withTask(task)).task();
    }

    default Task<?> getTask(String id) {
        return Optional.ofNullable(get(id)).map(TaskMetadata::task).orElse(null);
    }

    default boolean isEmpty() {
        return size() == 0;
    }
}
