package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.queue.DocumentSet;

public interface DocumentCollectionFactory {
    DocumentQueue createQueue(PropertiesProvider propertiesProvider, String queueName);
    DocumentSet createSet(PropertiesProvider propertiesProvider, String setName);
}
