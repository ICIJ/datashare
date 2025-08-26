package org.icij.datashare.asynctasks;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class TaskRepositoryMemory extends ConcurrentHashMap<String, TaskMetadata> implements TaskRepository {
    @Override
    public void insert(Task task, Group group) throws IOException {
        super.put(task.id, new TaskMetadata(task, group));
    }

    @Override
    public Task getTask(String taskId) throws IOException, UnknownTask {
        TaskMetadata taskMetadata = super.get(taskId);
        if (taskMetadata == null) {
            throw new UnknownTask(taskId);
        }
        return taskMetadata.task();
    }

    @Override
    public Stream<Task> getTasks(TaskFilters filters) throws IOException, UnknownTask {
        return super.values().stream().map(TaskMetadata::task).filter(filters::filter);
    }

    @Override
    public void update(Task task) throws IOException, UnknownTask {
        put(task.id, super.get(task.id).withTask(task));
    }

    @Override
    public Task delete(String taskId) throws IOException, UnknownTask {
        return Optional.ofNullable(remove(taskId)).orElseThrow(() -> new UnknownTask(taskId)).task();
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
