package org.icij.datashare.extract;

import org.icij.datashare.PropertiesProvider;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.report.ReportMap;

public interface DocumentCollectionFactory<T> {
    DocumentQueue<T> createQueue(PropertiesProvider propertiesProvider, String queueName, Class<T> clazz);
    ReportMap createMap(PropertiesProvider propertiesProvider, String mapName);
}
