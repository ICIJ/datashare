package org.icij.datashare.asynctasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.json.JsonObjectMapper;

import java.io.IOException;
import java.io.Serializable;
import java.util.stream.Stream;

public interface TaskRepository {
    ObjectMapper TYPE_INCLUSION_MAPPER = JsonObjectMapper.createTypeInclusionMapper();

    <V extends Serializable> void insert(Task<V> task, Group group) throws IOException, TaskAlreadyExists;

    <V extends Serializable> Task<V> getTask(String taskId) throws IOException, UnknownTask;

    <V extends Serializable> void update(Task<V> task) throws IOException, UnknownTask;

    <V extends Serializable> Task<V> delete(String taskId) throws IOException, UnknownTask;

    void deleteAll() throws IOException, UnknownTask;

    Group getTaskGroup(String taskId) throws IOException, UnknownTask;


    Stream<Task<? extends Serializable>> getTasks(TaskFilters filters) throws IOException, UnknownTask;

    default void registerTaskResultTypes(Class<? extends Serializable> ...classesToRegister) {
        TYPE_INCLUSION_MAPPER.registerSubtypes(classesToRegister);
    }
}
