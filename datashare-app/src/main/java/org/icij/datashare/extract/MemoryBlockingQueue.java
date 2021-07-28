package org.icij.datashare.extract;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;

import java.util.concurrent.LinkedBlockingQueue;

public class MemoryBlockingQueue<T> extends LinkedBlockingQueue<T> {
    public final String queueName;
    @Inject
    public MemoryBlockingQueue(PropertiesProvider propertiesProvider, @Assisted String queueName) { this.queueName = queueName; }
}
