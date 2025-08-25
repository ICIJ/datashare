package org.icij.datashare.tasks;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.icij.datashare.asynctasks.TaskManagerRedis;
import org.redisson.Redisson;
import org.redisson.RedissonMap;
import org.redisson.api.RedissonClient;
import org.redisson.command.CommandSyncService;
import org.redisson.liveobject.core.RedissonObjectBuilder;

public class RedisBatchSerializer extends RedissonMap<String, List<?>> implements BatchSerializer<String> {
    public static final String BATCH_MAP_NAME = "ds:batches";
    public static final String BATCH_TO_TASK_MAP_NAME = "ds:batchToTask";

    private final RedissonMap<String, String> batchToTaskId;

    public RedisBatchSerializer(RedissonClient redisson) {
        super(new TaskManagerRedis.RedisCodec<>(List.class), new CommandSyncService(((Redisson) redisson).getConnectionManager(), new RedissonObjectBuilder(redisson)),
            BATCH_MAP_NAME, redisson, null, null);
        batchToTaskId = new RedissonMap<>(new TaskManagerRedis.RedisCodec<>(String.class),
            new CommandSyncService(((Redisson) redisson).getConnectionManager(), new RedissonObjectBuilder(redisson)),
            BATCH_TO_TASK_MAP_NAME, redisson, null, null);
    }

    @Override
    public String addBatch(String collectionId, List<?> batch) {
        String batchId = makeBatchId(collectionId);
        synchronized (batchToTaskId) {
            put(batchId, batch);
            batchToTaskId.put(batchId, collectionId);
        }
        return batchId;
    }

    @Override
    public List<?> getBatch(String batchKey) {
        return super.get(batchKey);
    }

    @Override
    public Stream<String> keys(String collectionId) {
        return keySet().stream().filter(batchId -> batchToTaskId.get(batchId).equals(collectionId));
    }

    @Override
    public void deleteBatches(String collectionId) {
        this.keySet().forEach(batchId -> {
            if (batchToTaskId.get(batchId).equals(collectionId)) {
                synchronized (batchToTaskId) {
                    remove(batchId);
                    batchToTaskId.remove(batchId);
                }
            }
        });
    }

    private static String makeBatchId(String taskId) {
        return taskId + "-" + UUID.randomUUID();
    }
}