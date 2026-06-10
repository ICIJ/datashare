package org.icij.datashare.session;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.security.User;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Hasher;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Transaction;

import java.util.List;

import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SESSION_TTL_SECONDS;
import static org.icij.datashare.cli.DatashareCliOptions.SESSION_TTL_SECONDS_OPT;
import static org.icij.datashare.user.User.fromJson;

@Singleton
public class UsersIdProviderRedisCache implements UsersIdProviderCache {
    private final JedisPool redis;
    private final Integer ttl;

    @Inject
    public UsersIdProviderRedisCache(PropertiesProvider propertiesProvider) {
        redis = new JedisPool(propertiesProvider.get("redisAddress").orElse(EnvUtils.resolveUri("redis", "redis://redis:6379")));
        this.ttl = Integer.valueOf(propertiesProvider.get(SESSION_TTL_SECONDS_OPT).orElse(String.valueOf(DEFAULT_SESSION_TTL_SECONDS)));
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
            return user != null && Hasher.SHA_256.hash(password).equals(user.details.get("password")) ? new DatashareUser(user) : null;
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
            String key = user.login();
            String value = JsonObjectMapper.serialize(((DatashareUser) user).details);
            for (int attempt = 0; attempt < 10; attempt++) {
                jedis.watch(key);
                long existingTtl = jedis.ttl(key);
                Transaction transaction = jedis.multi();
                try {
                    transaction.set(key, value);
                    if (existingTtl == -2L) {
                        transaction.expire(key, this.ttl);
                    } else if (existingTtl > 0L) {
                        transaction.expire(key, (int) Math.max(existingTtl, this.ttl));
                    }
                    List<Object> exec = transaction.exec();
                    if (exec != null) {
                        return !exec.isEmpty();
                    }
                } catch (RuntimeException e) {
                    transaction.discard();
                    throw e;
                }
            }
            throw new IllegalStateException("saveOrUpdate for '" + key + "' failed after 10 WATCH conflicts");
        }
    }
}
