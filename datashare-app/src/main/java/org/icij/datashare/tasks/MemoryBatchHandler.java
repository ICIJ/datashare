package org.icij.datashare.tasks;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class MemoryBatchHandler<V> extends HashMap<String, List<?>> implements BatchHandler<String> {
    // Is it really needed ?
    private final ConcurrentHashMap<String, String> batchToTaskId = new ConcurrentHashMap<>();

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