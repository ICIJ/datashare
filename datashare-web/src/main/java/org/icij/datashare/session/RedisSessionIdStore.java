package org.icij.datashare.session;

import net.codestory.http.security.SessionIdStore;
import org.icij.datashare.PropertiesProvider;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;

import static java.util.Optional.ofNullable;

public class RedisSessionIdStore implements SessionIdStore {
    private final Jedis redis;
    private final Integer ttl;

    public RedisSessionIdStore(PropertiesProvider propertiesProvider) {
        this.redis = new Jedis(propertiesProvider.getProperties().getProperty("messageBusAddress"));
        this.ttl = Integer.valueOf(ofNullable(propertiesProvider.getProperties().getProperty("sessionTtlSeconds")).orElse("1"));
    }

    @Override
    public void put(final String sessionId, final String login) {
        Transaction transaction = this.redis.multi();
        transaction.set(sessionId, login);
        transaction.expire(sessionId, this.ttl);
        transaction.exec();
    }

    @Override
    public void remove(String sessionId) {
        this.redis.del(sessionId);
    }

    @Override
    public String getLogin(String sessionId) {
        return this.redis.get(sessionId);
    }
}
