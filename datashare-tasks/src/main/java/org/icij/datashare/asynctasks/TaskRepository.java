package org.icij.datashare.asynctasks;

import static org.icij.datashare.asynctasks.TaskStreamUtils.getFilteredTaskStream;

import java.io.IOException;
import java.io.Serializable;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public interface TaskRepository {
    <V extends Serializable> void insert(Task<V> task, Group group) throws IOException, TaskAlreadyExists;

    <V extends Serializable> Task<V> getTask(String taskId) throws IOException, UnknownTask;

    <V extends Serializable> void update(Task<V> task) throws IOException, UnknownTask;

    <V extends Serializable> Task<V> delete(String taskId) throws IOException, UnknownTask;

    void deleteAll() throws IOException, UnknownTask;

    Group getTaskGroup(String taskId) throws IOException, UnknownTask;


    Stream<Task<? extends Serializable>> getTasks() throws IOException, UnknownTask;

    default Stream<Task<? extends Serializable>> getTasks(Map<String, Pattern> filters, Set<Task.State> stateFilter) throws IOException {
        return getFilteredTaskStream(filters, getTasks().filter(t -> stateFilter.contains(t.getState())));
    }

}
