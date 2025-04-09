package org.icij.datashare.asynctasks;

import org.redisson.Redisson;
import org.redisson.RedissonMap;
import org.redisson.api.RedissonClient;
import org.redisson.command.CommandSyncService;
import org.redisson.liveobject.core.RedissonObjectBuilder;

public class TaskRepositoryRedis extends RedissonMap<String, TaskMetadata> implements TaskRepository {
    private final RedissonMap<String, byte[]> results;

    public TaskRepositoryRedis(RedissonClient redisson) {
        this(redisson, "ds:task:manager");
    }

    public TaskRepositoryRedis(RedissonClient redisson, String name) {
        super(new TaskManagerRedis.RedisCodec<>(TaskMetadata.class), new CommandSyncService(((Redisson) redisson).getConnectionManager(), new RedissonObjectBuilder(redisson)),
                name, redisson, null, null);
        String resultQueueName = name + ":results";
        this.results = new RedissonMap<>(new CommandSyncService(((Redisson) redisson).getConnectionManager(), new RedissonObjectBuilder(redisson)),
                resultQueueName, redisson, null, null);
    }

    @Override
    public void saveResult(String taskId, byte[] result) throws UnknownTask {
        if (!this.containsKey(taskId)) {
            throw new UnknownTask(taskId);
        }
        results.put(taskId, result);
    }

    @Override
    public byte[] getResult(String taskId) throws UnknownTask {
        if (!this.containsKey(taskId)) {
            throw new UnknownTask(taskId);
        }
        return results.get(taskId);
    }
}
