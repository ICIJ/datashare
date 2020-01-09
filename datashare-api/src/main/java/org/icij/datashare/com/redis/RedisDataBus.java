package org.icij.datashare.com.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Channel;
import org.icij.datashare.com.DataBus;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static java.util.Arrays.stream;
import static org.icij.datashare.com.Message.Type.SHUTDOWN;

public class RedisDataBus implements Publisher, DataBus, Closeable {
    private static Logger logger = LoggerFactory.getLogger(RedisDataBus.class);
    private final JedisPool redis;
    private final Map<Consumer<Message>, JedisListener> subscribers = new ConcurrentHashMap<>();

    @Inject
    public RedisDataBus(PropertiesProvider propertiesProvider) {
        this.redis = new JedisPool(new JedisPoolConfig(), propertiesProvider.get("messageBusAddress").orElse("redis"));
    }

    @Override
    public int subscribe(Consumer<Message> subscriber, Channel... channels) {
        return subscribe(subscriber, () -> logger.debug("subscribed to " + Arrays.toString(channels)), channels);
    }

    @Override
    public int subscribe(Consumer<Message> subscriber, Runnable subscriptionCallback, Channel... channels) {
        JedisListener jedisListener = new JedisListener(subscriber, subscriptionCallback);
        subscribers.put(subscriber, jedisListener);
        try (Jedis jedis = redis.getResource()) {
            jedis.subscribe(jedisListener, stream(channels).map(Enum::name).toArray(String[]::new));
        }
        return jedisListener.nbMessages.get();
    }

    @Override
    public void unsubscribe(Consumer<Message> subscriber) {
        subscribers.remove(subscriber).unsubscribe();
    }

    @Override
    public void publish(Channel channel, Message message) {
        try (Jedis jedis = redis.getResource()) {
            jedis.publish(channel.name(), message.toJson());
        }
    }

    @Override
    public void close() {
        this.redis.close();
    }

    static class JedisListener extends JedisPubSub {
        private final Consumer<Message> callback;
        private final Runnable subscribedCallback;
        final AtomicInteger nbMessages = new AtomicInteger(0);

        JedisListener(Consumer<Message> callback, Runnable subscribedCallback) {
            this.callback = callback;
            this.subscribedCallback = subscribedCallback;
        }

        @Override
        public void onSubscribe(String channel, int subscribedChannels) {
            subscribedCallback.run();
        }

        @Override
        public void onMessage(String channel, String message) {
            try {
                HashMap result = new ObjectMapper().readValue(message, HashMap.class);
                Message msg = new Message(result);
                if (msg.type == SHUTDOWN) {
                    unsubscribe();
                    logger.info("Shutdown called. Unsubscribe done.");
                }
                callback.accept(msg);
                nbMessages.getAndIncrement();
            } catch (IOException e) {
                logger.error("cannot deserialize json message " + message, e);
            }
        }
    }
}
