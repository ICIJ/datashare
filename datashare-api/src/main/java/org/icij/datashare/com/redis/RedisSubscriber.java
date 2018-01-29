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
import java.util.function.Function;

import static org.icij.datashare.com.Message.Type.SHUTDOWN;

public class RedisSubscriber implements Runnable {
    private static Logger logger = LoggerFactory.getLogger(RedisSubscriber.class);
    private final Jedis redis;
    private final Function<Message, Void> callback;
    private Channel channel;

    public RedisSubscriber(final Jedis redis, final Function<Message, Void> callback) {
        this.redis = redis;
        this.callback = callback;
    }

    public RedisSubscriber subscribe(Channel channel) {
        this.channel = channel;
        return this;
    }

    @Override
    public void run() {
        redis.subscribe(new JedisListener(callback), channel.name());
    }

    static class JedisListener extends JedisPubSub {
        private final Function<Message, Void> callback;

        JedisListener(Function<Message, Void> callback) {
            this.callback = callback;
        }

        @Override
        public void onMessage(String channel, String message) {
            try {
                HashMap result = new ObjectMapper().readValue(message, HashMap.class);
                Message msg = new Message(result);
                if (msg.type == SHUTDOWN) {
                    unsubscribe();
                    return;
                }
                callback.apply(msg);
            } catch (IOException e) {
                logger.error("cannot deserialize json message " + message, e);
            }
        }
    }
}
