package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.security.User;
import net.codestory.http.security.Users;
import org.icij.datashare.PropertiesProvider;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Transaction;

import static java.util.Optional.ofNullable;

public class RedisUsers implements Users {
    private final JedisPool redis;
    private final Integer ttl;

    @Inject
    public RedisUsers(PropertiesProvider propertiesProvider) {
        redis = new JedisPool(new JedisPoolConfig(), propertiesProvider.getProperties().getProperty("messageBusAddress"));
        this.ttl = Integer.valueOf(ofNullable(propertiesProvider.getProperties().getProperty("sessionTtlSeconds")).orElse("1"));
    }

    @Override
    public User find(String login, String password) {
        HashMapUser user = getUser(login);
        return user != null && user.get("password") != null && user.get("password").equals(password) ? user: null;
    }

    @Override
    public User find(String login) {
        return getUser(login);
    }

    void createUser(HashMapUser user) {
        try (Jedis jedis = redis.getResource()) {
            Transaction transaction = jedis.multi();
            transaction.hmset(user.login(), user.userMap);
            transaction.expire(user.login(), this.ttl);
            transaction.exec();
        }
    }

    HashMapUser getUser(String login) {
        try (Jedis jedis = redis.getResource()) {
            return new HashMapUser(jedis.hgetAll(login));
        }
    }

    void removeUser(String login) {
        try (Jedis jedis = redis.getResource()) {
            jedis.del(login);
        }
    }
}
