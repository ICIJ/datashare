package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.security.SessionIdStore;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Transaction;

import static java.util.Optional.ofNullable;

public class RedisSessionIdStore implements SessionIdStore {
    private final JedisPool redis;
    private final Integer ttl;

    @Inject
    public RedisSessionIdStore(PropertiesProvider propertiesProvider) {
        this.redis = new JedisPool(propertiesProvider.get("redisAddress").orElse(EnvUtils.resolveUri("redis", "redis://redis:6379")));
        this.ttl = Integer.valueOf(ofNullable(propertiesProvider.getProperties().getProperty("sessionTtlSeconds")).orElse("1"));
    }

    @Override
    public void put(final String sessionId, final String login) {
        try (Jedis jedis = redis.getResource()) {
            Transaction transaction = jedis.multi();
            transaction.set(sessionId, login);
            transaction.expire(sessionId, this.ttl);
            transaction.exec();
        }
    }

    @Override
    public void remove(String sessionId) {
        try (Jedis jedis = redis.getResource()) {
            jedis.del(sessionId);
        }
    }

    @Override
    public String getLogin(String sessionId) {
        try (Jedis jedis = redis.getResource()) {
            return jedis.get(sessionId);
        }
    }
}
