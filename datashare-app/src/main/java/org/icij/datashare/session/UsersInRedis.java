package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.security.User;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Hasher;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Transaction;

import java.util.List;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.user.User.fromJson;

public class UsersInRedis implements UsersWritable {
    private final JedisPool redis;
    private final Integer ttl;

    @Inject
    public UsersInRedis(PropertiesProvider propertiesProvider) {
        redis = new JedisPool(propertiesProvider.get("redisAddress").orElse(EnvUtils.resolveUri("redis", "redis://redis:6379")));
        this.ttl = Integer.valueOf(ofNullable(propertiesProvider.getProperties().getProperty("sessionTtlSeconds")).orElse("1"));
    }

    @Override
    public User find(String login) {
        try (Jedis jedis = redis.getResource()) {
            org.icij.datashare.user.User user = fromJson(jedis.get(login), "icij");
            return user != null ? new DatashareUser(user) : null;
        }
    }

    @Override
    public User find(String login, String password) {
        try (Jedis jedis = redis.getResource()) {
            org.icij.datashare.user.User user = fromJson(jedis.get(login), "icij");
            return user != null && Hasher.SHA_256.hash(password).equals(user.details.get("password")) ? new DatashareUser(user): null;
        }
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
            transaction.set(user.login(), JsonObjectMapper.serialize(((DatashareUser)user).details));
            transaction.expire(user.login(), this.ttl);
            List<Object> exec = transaction.exec();
            return exec.size() == 2;
        }
    }
}
