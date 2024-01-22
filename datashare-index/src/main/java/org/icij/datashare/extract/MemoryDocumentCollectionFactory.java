package org.icij.datashare.extract;

import org.icij.datashare.PropertiesProvider;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.queue.MemoryDocumentQueue;
import org.icij.extract.report.HashMapReportMap;
import org.icij.extract.report.ReportMap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MemoryDocumentCollectionFactory<T> implements DocumentCollectionFactory<T> {
    public final Map<String, DocumentQueue<T>> queues = new ConcurrentHashMap<>();
    final Map<String, ReportMap> maps = new ConcurrentHashMap<>();

    @Override
    public DocumentQueue<T> createQueue(PropertiesProvider propertiesProvider, String queueName, Class<T> clazz) {
        if (!queues.containsKey(queueName)) {
            synchronized (queues) {
                queues.putIfAbsent(queueName, new MemoryDocumentQueue<>(queueName, 1024));
            }
        }
        return queues.get(queueName);
    }

    @Override
    public ReportMap createMap(PropertiesProvider propertiesProvider, String mapName) {
        if (!maps.containsKey(mapName)) {
            synchronized (maps) {
                maps.putIfAbsent(mapName, new HashMapReportMap());
            }
        }
        return maps.get(mapName);
    }
}
