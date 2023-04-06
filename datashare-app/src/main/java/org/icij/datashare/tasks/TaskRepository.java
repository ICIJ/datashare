package org.icij.datashare.tasks;

import java.util.List;

interface TaskRepository {
    <V> Void save(TaskViewInterface<V> task);
    TaskViewInterface<?> get(String id);
    List<TaskViewInterface<?>> get();
    List<TaskViewInterface<?>> clearDoneTasks();
}
