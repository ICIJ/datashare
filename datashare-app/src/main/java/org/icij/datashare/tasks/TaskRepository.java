package org.icij.datashare.tasks;

import java.util.List;

interface TaskRepository {
    <V> void save(TaskView<V> task);
    TaskView<?> get(String id);
    List<TaskView<?>> get();
    List<TaskView<?>> clearDoneTasks();
}
