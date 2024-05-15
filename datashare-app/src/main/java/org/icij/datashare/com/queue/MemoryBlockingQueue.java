package org.icij.datashare.com.queue;

import java.util.concurrent.LinkedBlockingQueue;

public class MemoryBlockingQueue<T> extends LinkedBlockingQueue<T> {
    public final String queueName;
    public MemoryBlockingQueue(String queueName) { this.queueName = queueName; }
}
