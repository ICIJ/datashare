package org.icij.datashare.session;

import org.icij.datashare.PropertiesProvider;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;

import java.util.Map;

import static java.util.Optional.ofNullable;

public class RedisSessionManager implements SessionManager {
    private final Jedis redis;
    private final Integer ttl;

    public RedisSessionManager(PropertiesProvider propertiesProvider) {
        this.redis = new Jedis(propertiesProvider.getProperties().getProperty("messageBusAddress"));
        this.ttl = Integer.valueOf(ofNullable(propertiesProvider.getProperties().getProperty("sessionTtlSeconds")).orElse("0"));
    }

    @Override
    public void createSession(String id, Map<String, String> sessionMap) {
        Transaction transaction = this.redis.multi();
        transaction.hmset(id, sessionMap);
        transaction.expire(id, this.ttl);
        transaction.exec();
    }

    @Override
    public Map<String, String> getSession(String id) {
        return this.redis.hgetAll(id);
    }
}
