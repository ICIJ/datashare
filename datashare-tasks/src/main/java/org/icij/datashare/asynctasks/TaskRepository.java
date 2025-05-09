package org.icij.datashare.asynctasks;

import java.io.IOException;
import java.io.Serializable;
import java.util.stream.Stream;

public interface TaskRepository {
    <V extends Serializable> void insert(Task<V> task, Group group) throws IOException, TaskAlreadyExists;

    <V extends Serializable> Task<V> getTask(String taskId) throws IOException, UnknownTask;

    <V extends Serializable> void update(Task<V> task) throws IOException, UnknownTask;

    <V extends Serializable> Task<V> delete(String taskId) throws IOException, UnknownTask;

    void deleteAll() throws IOException, UnknownTask;

    Group getTaskGroup(String taskId) throws IOException, UnknownTask;


    Stream<Task<? extends Serializable>> getTasks(TaskFilters filters) throws IOException, UnknownTask;
}
