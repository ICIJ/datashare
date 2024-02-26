package org.icij.datashare.extract;

import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.report.ReportMap;

import java.util.List;

public interface DocumentCollectionFactory<T> {
    DocumentQueue<T> createQueue(String queueName, Class<T> clazz);
    ReportMap createMap(String mapName);
    List<DocumentQueue<T>> getQueues(Class<T> clazz);
    List<DocumentQueue<T>> getQueues(String wildcardMatcher, Class<T> clazz);
}
