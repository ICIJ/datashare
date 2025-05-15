package org.icij.datashare.asynctasks;

import java.io.IOException;
import java.util.stream.Stream;

public interface TaskRepository {
    void insert(Task task, Group group) throws IOException, TaskAlreadyExists;

    Task getTask(String taskId) throws IOException, UnknownTask;

    void update(Task task) throws IOException, UnknownTask;

    Task delete(String taskId) throws IOException, UnknownTask;

    void deleteAll() throws IOException, UnknownTask;

    Group getTaskGroup(String taskId) throws IOException, UnknownTask;

    Stream<Task> getTasks(TaskFilters filters) throws IOException, UnknownTask;
}
