package org.icij.datashare.com.memory;

import org.icij.datashare.com.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.CollectionUtils.asSet;

public class MemoryDataBus implements Publisher, DataBus {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Map<Consumer<Message>, MessageListener> subscribers = new ConcurrentHashMap<>();

    public void publish(final Channel channel, final Message message) {
        subscribers.values().stream().filter(l -> l.hasSubscribedTo(channel)).forEach(l -> l.accept(message));
    }

    @Override
    public int subscribe(final Consumer<Message> subscriber, final Channel... channels) throws InterruptedException {
        return subscribe(subscriber, () -> logger.debug("subscribed {} to {}", subscriber, Arrays.toString(channels)), channels);
    }

    @Override
    public int subscribe(final Consumer<Message> subscriber, final Runnable subscriptionCallback, final Channel... channels) throws InterruptedException {
        MessageListener listener = new MessageListener(subscriber, channels);
        subscribers.put(subscriber, listener);
        subscriptionCallback.run();

        synchronized (listener.message) {
            while (!listener.isShutdown()) {
                listener.message.wait();
            }
        }
        return listener.nbMessages.get();
    }

    @Override
    public void unsubscribe(Consumer<Message> subscriber) {
        ofNullable(subscribers.remove(subscriber)).ifPresent(l -> {
            l.accept(new ShutdownMessage());
            logger.debug("unsubscribed {}", subscriber);
        });
    }

    private static class MessageListener implements Consumer<Message> {
        private final Consumer<Message> subscriber;
        private final LinkedHashSet<Channel> channels;
        final AtomicReference<Message> message = new AtomicReference<>();
        final AtomicInteger nbMessages = new AtomicInteger(0);

        public MessageListener(Consumer<Message> subscriber, Channel... channels) {
            this.subscriber = subscriber;
            this.channels = asSet(channels);
        }

        boolean hasSubscribedTo(Channel channel) {
            return channels.contains(channel);
        }

        @Override
        public void accept(Message message) {
            this.message.set(message);
            subscriber.accept(message);
            nbMessages.getAndIncrement();
            synchronized (this.message) {
                this.message.notify();
            }
        }

        boolean isShutdown() {
            return this.message.get() != null && this.message.get().type == Message.Type.SHUTDOWN;
        }
    }
}
