package org.icij.datashare.extract;

import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.report.ReportMap;

public interface DocumentCollectionFactory<T> {
    DocumentQueue<T> createQueue(String queueName, Class<T> clazz);
    ReportMap createMap(String mapName);
}
