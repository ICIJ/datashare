package org.icij.datashare.com.queue;

import com.google.inject.Singleton;

import java.util.concurrent.LinkedBlockingQueue;

@Singleton
public class MemoryBlockingQueue<T> extends LinkedBlockingQueue<T> {
    public final String queueName;
    public MemoryBlockingQueue(String queueName) { this.queueName = queueName; }
}
