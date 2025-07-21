package org.icij.datashare.extract;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.icij.datashare.PropertiesProvider;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.queue.MemoryDocumentQueue;
import org.icij.extract.report.HashMapReportMap;
import org.icij.extract.report.ReportMap;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.apache.commons.io.FilenameUtils.wildcardMatch;
import static org.icij.datashare.PropertiesProvider.DEFAULT_QUEUE_CAPACITY;

@Singleton
public class MemoryDocumentCollectionFactory<T> implements DocumentCollectionFactory<T> {
    public final Map<String, DocumentQueue<T>> queues = new ConcurrentHashMap<>();
    final Map<String, ReportMap> maps = new ConcurrentHashMap<>();
    private final int queueCapacity;

    @Inject
    public MemoryDocumentCollectionFactory() {
        this.queueCapacity = DEFAULT_QUEUE_CAPACITY;
    }

    @Inject
    public MemoryDocumentCollectionFactory(final PropertiesProvider propertiesProvider) {
        this.queueCapacity = propertiesProvider.queueCapacity() ;
    }

    @Override
    public DocumentQueue<T> createQueue(String queueName, Class<T> clazz) {
        synchronized (queues) {
            if (!queues.containsKey(queueName)) {
                queues.put(queueName, new MemoryDocumentQueue<>(queueName, queueCapacity));
            }
        }
        return queues.get(queueName);
    }

    @Override
    public ReportMap createMap(String mapName) {
        synchronized (maps) {
            if (!maps.containsKey(mapName)) {
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
