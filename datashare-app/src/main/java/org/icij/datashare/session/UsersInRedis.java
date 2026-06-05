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
            String key = user.login();
            String value = JsonObjectMapper.serialize(((DatashareUser) user).details);
            // WATCH + retry loop to eliminate the TOCTOU race between ttl() and EXEC:
            // without WATCH, a concurrent login could create the key with a long TTL
            // after we read -2, then our EXEC would overwrite it with EXPIRE=1s.
            // WATCH makes EXEC return null if the key changes; we re-read the TTL and retry.
            //
            // TTL extend-only rule (applied atomically per iteration):
            //   -2 (key absent)  → apply this.ttl on new key
            //   >0 (live session) → max(existing, this.ttl): never shorten
            //   -1 (persistent)   → omit EXPIRE; SET already cleared the TTL, keep persistent
            while (true) {
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
                    // exec == null: WATCH conflict — key was modified between watch() and exec().
                    // Loop retries with a fresh TTL read.
                } catch (RuntimeException e) {
                    transaction.discard();
                    throw e;
                }
            }
        }
    }
}
