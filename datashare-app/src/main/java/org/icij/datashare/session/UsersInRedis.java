package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.security.User;
import org.icij.datashare.PropertiesProvider;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Transaction;

import java.util.List;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.user.User.fromJson;

public class UsersInRedis implements UsersWritable {
    private final JedisPool redis;
    private final Integer ttl;

    @Inject
    public UsersInRedis(PropertiesProvider propertiesProvider) {
        redis = new JedisPool(new JedisPoolConfig(), propertiesProvider.getProperties().getProperty("messageBusAddress"));
        this.ttl = Integer.valueOf(ofNullable(propertiesProvider.getProperties().getProperty("sessionTtlSeconds")).orElse("1"));
    }

    @Override
    public User find(String login) {
        try (Jedis jedis = redis.getResource()) {
            return new DatashareUser(fromJson(jedis.get(login), "icij"));
        }
    }

    @Override
    public User find(String login, String password) {
        return null;
    }

    void removeUser(String login) {
        try (Jedis jedis = redis.getResource()) {
            jedis.del(login);
        }
    }

    @Override
    public boolean saveOrUpdate(User user) {
        try (Jedis jedis = redis.getResource()) {
            Transaction transaction = jedis.multi();
            transaction.set(user.login(), ((DatashareUser)user).getJsonDetails());
            transaction.expire(user.login(), this.ttl);
            List<Object> exec = transaction.exec();
            return exec.size() == 2;
        }
    }
}
