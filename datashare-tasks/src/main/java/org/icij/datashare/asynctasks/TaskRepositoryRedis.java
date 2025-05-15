package org.icij.datashare.asynctasks;

import java.io.IOException;
import java.util.Optional;
import java.util.stream.Stream;
import org.redisson.Redisson;
import org.redisson.RedissonMap;
import org.redisson.api.RedissonClient;
import org.redisson.command.CommandSyncService;
import org.redisson.liveobject.core.RedissonObjectBuilder;

public class TaskRepositoryRedis extends RedissonMap<String, TaskMetadata> implements TaskRepository {
    public TaskRepositoryRedis(RedissonClient redisson) {
        this(redisson, "ds:task:manager");
    }

    public TaskRepositoryRedis(RedissonClient redisson, String name) {
        super(new TaskManagerRedis.RedisCodec<>(TaskMetadata.class), new CommandSyncService(((Redisson) redisson).getConnectionManager(), new RedissonObjectBuilder(redisson)),
                name, redisson, null, null);
    }

    @Override
    public Task getTask(String taskId) throws IOException, UnknownTask{
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
    public void insert(Task task, Group group) throws IOException {
        super.put(task.id, new TaskMetadata(task, group));
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
