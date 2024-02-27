package org.icij.datashare.extract;

import com.google.inject.Singleton;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.queue.MemoryDocumentQueue;
import org.icij.extract.report.HashMapReportMap;
import org.icij.extract.report.ReportMap;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.apache.commons.io.FilenameUtils.wildcardMatch;

@Singleton
public class MemoryDocumentCollectionFactory<T> implements DocumentCollectionFactory<T> {
    public final Map<String, DocumentQueue<T>> queues = new ConcurrentHashMap<>();
    final Map<String, ReportMap> maps = new ConcurrentHashMap<>();
    // The size of the internal file path buffer used by the queue
    final int QUEUE_CAPACITY = (int) 1e6;

    @Override
    public DocumentQueue<T> createQueue(String queueName, Class<T> clazz) {
        if (!queues.containsKey(queueName)) {
            synchronized (queues) {
                queues.put(queueName, new MemoryDocumentQueue<>(queueName, QUEUE_CAPACITY));
            }
        }
        return queues.get(queueName);
    }

    @Override
    public ReportMap createMap(String mapName) {
        if (!maps.containsKey(mapName)) {
            synchronized (maps) {
                maps.putIfAbsent(mapName, new HashMapReportMap());
            }
        }
        return maps.get(mapName);
    }

    @Override
    public List<DocumentQueue<T>> getQueues(String wildcardMatcher, Class<T> clazz) {
        return queues
                .keySet()
                .stream()
                .filter(name -> wildcardMatch(name, wildcardMatcher))
                .map(k -> createQueue(k, clazz))
                .collect(Collectors.toList());
    }

    @Override
    public List<DocumentQueue<T>> getQueues(Class<T> clazz) {
        return getQueues("*", clazz);
    }
}
