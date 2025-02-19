package org.icij.datashare.asynctasks;

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
        super(new TaskManagerRedis.RedisCodec<>(TaskMetadata.class), new CommandSyncService(((Redisson) redisson).getConnectionManager(), new RedissonObjectBuilder(redisson)),
                name, redisson, null, null);
    }
}
