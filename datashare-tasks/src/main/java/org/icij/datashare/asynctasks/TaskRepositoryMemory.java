package org.icij.datashare.asynctasks;

import java.io.IOException;
import java.io.Serializable;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class TaskRepositoryMemory extends ConcurrentHashMap<String, TaskGroupMetadata<?>> implements TaskRepository {
    @Override
    public <V extends Serializable> void insert(Task<V> task, Group group) throws IOException {
        super.put(task.id, new TaskGroupMetadata<>(task, group));
    }

    @Override
    public <V extends Serializable> Task<V> getTask(String taskId) throws IOException, UnknownTask {
        TaskGroupMetadata<V> taskGroupMetadata = (TaskGroupMetadata<V>) super.get(taskId);
        if (taskGroupMetadata == null) {
            throw new UnknownTask(taskId);
        }
        return taskGroupMetadata.task();
    }

    @Override
    public Stream<Task<? extends Serializable>> getTasks(TaskFilters filters) throws IOException, UnknownTask {
        return super.values().stream().map(TaskGroupMetadata::task)
            .filter(filters::filter)
            .map(t -> (Task<? extends Serializable>)t);
    }

    @Override
    public Stream<String> getTaskIds(TaskFilters filters) throws IOException, UnknownTask {
        // Inefficient implementation of get task state but that's fine for in-memory usage
        return getTasks(filters).map(Task::getId);
    }

    @Override
    public <V extends Serializable> void update(Task<V> task) throws IOException, UnknownTask {
        put(task.id, super.get(task.id).withTask(task));
    }

    @Override
    public <V extends Serializable> Task<V> delete(String taskId) throws IOException, UnknownTask {
        return (Task<V>) Optional.ofNullable(remove(taskId)).orElseThrow(() -> new UnknownTask(taskId)).task();
    }

    @Override
    public void deleteAll() throws IOException, UnknownTask {
        clear();
    }

    @Override
    public Group getTaskGroup(String taskId) throws IOException, UnknownTask {
        return super.get(taskId).group();
    }
}
