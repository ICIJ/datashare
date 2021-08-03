package org.icij.datashare.tasks;

import java.util.List;

interface TaskRepository {
    <V> boolean save(TaskView<V> task);
    TaskView<?> get(String id);
    List<TaskView<?>> clearDoneTasks();
}
