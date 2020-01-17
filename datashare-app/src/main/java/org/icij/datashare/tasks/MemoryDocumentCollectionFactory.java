package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.queue.MemoryDocumentQueue;
import org.icij.extract.report.HashMapReportMap;
import org.icij.extract.report.ReportMap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MemoryDocumentCollectionFactory implements DocumentCollectionFactory {
    final Map<String, DocumentQueue> queues = new ConcurrentHashMap<>();
    final Map<String, ReportMap> maps = new ConcurrentHashMap<>();

    @Override
    public DocumentQueue createQueue(PropertiesProvider propertiesProvider, String queueName) {
        if (!queues.containsKey(queueName)) {
            synchronized (queues) {
                queues.putIfAbsent(queueName, new MemoryDocumentQueue(queueName, 1024));
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
