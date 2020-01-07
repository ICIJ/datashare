package org.icij.datashare.com.memory;

import org.icij.datashare.com.Channel;
import org.icij.datashare.com.DataBus;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.Publisher;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.icij.datashare.CollectionUtils.asSet;

public class MemoryDataBus implements Publisher, DataBus {
    private final Map<Consumer<Message>, Set<Channel>> subscribers = new ConcurrentHashMap<>();

    public void publish(Channel channel, Message message) {
        subscribers.entrySet().stream().filter(e -> e.getValue().contains(channel)).forEach(e -> e.getKey().accept(message));
    }

    @Override
    public void subscribe(Consumer<Message> subscriber, Channel... channels) {
        subscribers.put(subscriber, asSet(channels));
    }

    @Override
    public void subscribe(Consumer<Message> subscriber, Runnable subscriptionCallback, Channel... channels) {
        subscribe(subscriber, channels);
        subscriptionCallback.run();
    }

    @Override
    public void unsubscribe(Consumer<Message> subscriber) {
        subscribers.remove(subscriber);
    }

}
