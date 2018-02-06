package org.icij.datashare.com.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.com.Channel;
import org.icij.datashare.com.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.io.IOException;
import java.util.HashMap;
import java.util.function.Consumer;

import static org.icij.datashare.com.Message.Type.SHUTDOWN;

public class RedisSubscriber implements Runnable {
    private static Logger logger = LoggerFactory.getLogger(RedisSubscriber.class);
    private final Jedis redis;
    private final Consumer<Message> callback;
    private final Runnable subscribedCallback;
    private Channel channel;

    public RedisSubscriber(final Jedis redis, final Consumer<Message> callback) {
        this.redis = redis;
        this.callback = callback;
        subscribedCallback = () -> logger.debug("subscribed done");
    }

    public RedisSubscriber(final Jedis redis, final Consumer<Message> callback, final Runnable subscribedCallback) {
        this.redis = redis;
        this.callback = callback;
        this.subscribedCallback = subscribedCallback;
    }

    public RedisSubscriber subscribe(Channel channel) {
        this.channel = channel;
        return this;
    }

    @Override
    public void run() {
        redis.subscribe(new JedisListener(callback, subscribedCallback), channel.name());
    }

    static class JedisListener extends JedisPubSub {
        private final Consumer<Message> callback;
        private final Runnable subscribedCallback;

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
                    return;
                }
                callback.accept(msg);
            } catch (IOException e) {
                logger.error("cannot deserialize json message " + message, e);
            }
        }
    }
}
