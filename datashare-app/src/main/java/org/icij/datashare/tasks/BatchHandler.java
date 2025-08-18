package org.icij.datashare.tasks;

import static org.icij.datashare.LambdaExceptionUtils.rethrowFunction;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

public interface BatchHandler<K> {
    K addBatch(String collectionId, List<?> batch) throws IOException;

    default <V> Stream<K> addBatches(String taskId, Stream<List<V>> batches) throws IOException {
        return batches.map(rethrowFunction(b -> addBatch(taskId, b)));
    }

    List<?> getBatch(K batchKey) throws IOException;

    Stream<K> keys(String collectionId) throws IOException;

    default int size(String collectionId) throws IOException {
        return keys(collectionId).map(k -> 1).reduce(0, Integer::sum);
    }

    void deleteBatches(String collectionId) throws IOException;
}