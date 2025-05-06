package org.icij.datashare.asynctasks;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class TaskRepositoryMemory extends ConcurrentHashMap<String, TaskMetadata> implements TaskRepository {
    private final ConcurrentHashMap<String, byte[]> results = new ConcurrentHashMap<>();

    @Override
    public void saveResult(String taskId, byte[] result) throws UnknownTask {
        results.put(taskId, result);
    }

    @Override
    public byte[] getResult(String taskId) throws UnknownTask {
        return Optional.ofNullable(results.get(taskId)).orElseThrow(() -> new UnknownTask(taskId));
    }
}
