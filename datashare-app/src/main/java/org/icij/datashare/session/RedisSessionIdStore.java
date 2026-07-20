package org.icij.datashare.session;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.security.SessionIdStore;
import org.icij.datashare.PropertiesProvider;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Transaction;

import java.io.Closeable;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.cli.DatashareCliOptions.SESSION_TTL_SECONDS_OPT;

@Singleton
public class RedisSessionIdStore implements SessionIdStore, Closeable {
    private final JedisPool redis;
    private final Integer ttl;

    @Inject
    public RedisSessionIdStore(PropertiesProvider propertiesProvider) {
        this.redis = RedisPoolFactory.createPool(propertiesProvider);
        this.ttl = Integer.valueOf(ofNullable(propertiesProvider.getProperties().getProperty(SESSION_TTL_SECONDS_OPT)).orElse("1"));
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

    @Override
    public void close() {
        redis.close();
    }
}
