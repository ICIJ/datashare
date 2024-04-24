package org.icij.datashare.asynctasks.bus.memory;

import java.util.concurrent.LinkedBlockingQueue;

public class MemoryBlockingQueue<T> extends LinkedBlockingQueue<T> {
    public final String queueName;
    public MemoryBlockingQueue(String queueName) { this.queueName = queueName; }
}
