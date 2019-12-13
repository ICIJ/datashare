package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;

import java.util.concurrent.BlockingQueue;

public interface QueueFactory<T> {
    BlockingQueue<T> create(PropertiesProvider propertiesProvider);
}
