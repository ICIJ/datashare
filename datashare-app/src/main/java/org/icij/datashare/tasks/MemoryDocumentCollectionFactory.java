package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.queue.DocumentSet;
import org.icij.extract.queue.MemoryDocumentQueue;
import org.icij.extract.queue.MemoryDocumentSet;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MemoryDocumentCollectionFactory implements DocumentCollectionFactory {
    final Map<String, DocumentQueue> queues = new ConcurrentHashMap<>();
    final Map<String, DocumentSet> sets = new ConcurrentHashMap<>();

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
}
