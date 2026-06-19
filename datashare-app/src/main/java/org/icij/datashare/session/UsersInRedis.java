package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.security.User;

import java.util.List;
import java.util.Set;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.user.admin.UserFilter;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Hasher;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.user.User.fromJson;

public class UsersInRedis implements UserStore {
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

    @Override
    public boolean save(org.icij.datashare.user.User user) {
        try (Jedis jedis = redis.getResource()) {
            jedis.set(user.id, JsonObjectMapper.serialize(user.details));
            jedis.sadd("_datashare_users", user.id);
            return true;
        }
    }

    @Override
    public boolean delete(String login) {
        try (Jedis jedis = redis.getResource()) {
            long deleted = jedis.del(login);
            if (deleted > 0) {
                jedis.srem("_datashare_users", login);
            }
            return deleted > 0;
        }
    }

    @Override
    public List<org.icij.datashare.user.User> listUsers(UserFilter filter) {
        try (Jedis jedis = redis.getResource()) {
            Set<String> logins = jedis.smembers("_datashare_users");
            if (logins.isEmpty()) {
                return List.of();
            }
            List<String> jsons = jedis.mget(logins.toArray(new String[0]));
            return jsons.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(json -> fromJson(json, "icij"))
                    .filter(java.util.Objects::nonNull)
                    .filter(filter::matches)
                    .map(DatashareUser::new)
                    .collect(java.util.stream.Collectors.toList());
        }
    }
}
