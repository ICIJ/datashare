package org.icij.datashare.asynctasks;

import java.io.IOException;
import java.io.Serializable;
import java.util.Optional;
import java.util.stream.Stream;
import org.redisson.Redisson;
import org.redisson.RedissonMap;
import org.redisson.api.RedissonClient;
import org.redisson.command.CommandSyncService;
import org.redisson.liveobject.core.RedissonObjectBuilder;

public class TaskRepositoryRedis extends RedissonMap<String, TaskMetadata<?>> implements TaskRepository {
    public TaskRepositoryRedis(RedissonClient redisson) {
        this(redisson, "ds:task:manager");
    }

    public TaskRepositoryRedis(RedissonClient redisson, String name) {
        super(new TaskManagerRedis.RedisCodec<>(TaskMetadata.class, TYPE_INCLUSION_MAPPER), new CommandSyncService(((Redisson) redisson).getConnectionManager(), new RedissonObjectBuilder(redisson)),
                name, redisson, null, null);
    }

    @Override
    public <V extends Serializable> Task<V> getTask(String taskId) throws IOException, UnknownTask{
        TaskMetadata<V> taskMetadata = (TaskMetadata<V>) super.get(taskId);
        if (taskMetadata == null) {
            throw new UnknownTask(taskId);
        }
        return taskMetadata.task();
    }

    @Override
    public Stream<Task<? extends Serializable>> getTasks(TaskFilters filters) throws IOException, UnknownTask {
        return super.values().stream().map(TaskMetadata::task)
            .filter(filters::filter)
            .map(t -> (Task<? extends Serializable>)t);
    }

    @Override
    public <V extends Serializable> void insert(Task<V> task, Group group) throws IOException {
        super.put(task.id, new TaskMetadata<>(task, group));
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
