package org.icij.datashare.com.redis;

import com.google.inject.Inject;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Channel;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.Publisher;
import redis.clients.jedis.Jedis;

public class RedisPublisher implements Publisher {
    private final Jedis redis;

    @Inject
    public RedisPublisher(PropertiesProvider propertiesProvider) {
        this.redis = new Jedis(propertiesProvider.getProperties().getProperty("messageBusAddress"));
    }

    RedisPublisher(final Jedis redis) { this.redis = redis;}

    @Override
    public void publish(Channel channel, Message message) {
        redis.publish(channel.name(), message.toJson());
    }
}
