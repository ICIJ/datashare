package org.icij.datashare.session;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.security.User;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Hasher;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Transaction;

import java.net.URI;
import java.util.List;

import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_REDIS_ADDRESS;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SESSION_TTL_SECONDS;
import static org.icij.datashare.cli.DatashareCliOptions.REDIS_ADDRESS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SESSION_TTL_SECONDS_OPT;
import static org.icij.datashare.user.User.fromJson;

@Singleton
public class UsersIdProviderRedisCache implements UsersIdProviderCache {
    private final JedisPool redis;
    private final Integer ttl;

    @Inject
    public UsersIdProviderRedisCache(PropertiesProvider propertiesProvider) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setTestOnBorrow(true);
        String redisAddress = propertiesProvider.get(REDIS_ADDRESS_OPT).orElse(DEFAULT_REDIS_ADDRESS);
        redis = new JedisPool(poolConfig, URI.create(redisAddress));
        this.ttl = Integer.valueOf(propertiesProvider.get(SESSION_TTL_SECONDS_OPT).orElse(String.valueOf(DEFAULT_SESSION_TTL_SECONDS)));
    }

    @Override
    public User find(String login) {
        try (Jedis jedis = redis.getResource()) {
            org.icij.datashare.user.User user = fromJson(jedis.get(login));
            return user != null ? new DatashareUser(user) : null;
        }
    }

    @Override
    public User find(String login, String password) {
        try (Jedis jedis = redis.getResource()) {
            org.icij.datashare.user.User user = fromJson(jedis.get(login));
            return user != null && Hasher.SHA_256.hash(password).equals(user.details.get("password")) ? new DatashareUser(user) : null;
        }
    }

    void removeUser(String login) {
        try (Jedis jedis = redis.getResource()) {
            jedis.del(login);
        }
    }

    public boolean saveOrUpdate(User user) {
        try (Jedis jedis = redis.getResource()) {
            long existingTtl = jedis.ttl(user.login());
            Transaction transaction = jedis.multi();
            transaction.set(user.login(), JsonObjectMapper.serialize(((DatashareUser)user).details));
            // -1 = persistent key: never add an expiry
            // -2 = new key: apply configured TTL
            // existing TTL >= configured TTL: don't shorten an active session
            if (existingTtl != -1 && (existingTtl == -2 || existingTtl < this.ttl)) {
                transaction.expire(user.login(), this.ttl);
            }
            List<Object> exec = transaction.exec();
            return !exec.isEmpty();
        }
    }
}
