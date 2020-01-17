package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.queue.DocumentSet;
import org.icij.extract.queue.MemoryDocumentQueue;
import org.icij.extract.queue.MemoryDocumentSet;
import org.icij.extract.report.HashMapReportMap;
import org.icij.extract.report.ReportMap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MemoryDocumentCollectionFactory implements DocumentCollectionFactory {
    final Map<String, DocumentQueue> queues = new ConcurrentHashMap<>();
    final Map<String, DocumentSet> sets = new ConcurrentHashMap<>();
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
    public DocumentSet createSet(PropertiesProvider propertiesProvider, String setName) {
        if (!sets.containsKey(setName)) {
            synchronized (sets) {
                sets.putIfAbsent(setName, new MemoryDocumentSet(setName));
            }
        }
        return sets.get(setName);
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
