package org.icij.datashare.session;

import org.icij.datashare.PropertiesProvider;
import redis.clients.jedis.Jedis;

import java.util.Map;

public class RedisSessionManager {
    private final Jedis redis;
    public RedisSessionManager(PropertiesProvider propertiesProvider) {
        this.redis = new Jedis(propertiesProvider.getProperties().getProperty("messageBusAddress"));
    }

    public void createSession(String id, Map<String, String> sessionMap) {
        this.redis.hmset(id, sessionMap);
    }

    public Map<String, String> getSession(String id) {
        return this.redis.hgetAll(id);
    }
}
