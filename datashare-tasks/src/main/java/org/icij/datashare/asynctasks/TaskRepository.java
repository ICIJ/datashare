package org.icij.datashare.asynctasks;

import java.util.Map;

public interface TaskRepository extends Map<String, Task<?>> {
    default Task<?> save(Task<?> task) {
        return put(task.getId(), task);
    }

    default Task<?> get(String id) {
        return get((Object)id);
    }

    default boolean isEmpty() {
        return size() == 0;
    }
}
