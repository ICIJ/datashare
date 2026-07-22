package org.icij.datashare.session;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.security.User;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Hasher;
import redis.clients.jedis.AbstractTransaction;
import redis.clients.jedis.JedisPooled;

import java.io.Closeable;
import java.util.List;

import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SESSION_TTL_SECONDS;
import static org.icij.datashare.cli.DatashareCliOptions.SESSION_TTL_SECONDS_OPT;
import static org.icij.datashare.user.User.fromJson;

@Singleton
public class UsersIdProviderRedisCache implements UsersIdProviderCache, Closeable {
    private final JedisPooled redis;
    private final Integer ttl;

    @Inject
    public UsersIdProviderRedisCache(PropertiesProvider propertiesProvider) {
        redis = RedisPoolFactory.createPool(propertiesProvider);
        this.ttl = Integer.valueOf(propertiesProvider.get(SESSION_TTL_SECONDS_OPT).orElse(String.valueOf(DEFAULT_SESSION_TTL_SECONDS)));
    }

    @Override
    public User find(String login) {
        org.icij.datashare.user.User user = fromJson(redis.get(login));
        return user != null ? new DatashareUser(user) : null;
    }

    @Override
    public User find(String login, String password) {
        org.icij.datashare.user.User user = fromJson(redis.get(login));
        return user != null && Hasher.SHA_256.hash(password).equals(user.details.get("password")) ? new DatashareUser(user) : null;
    }

    void removeUser(String login) {
        redis.del(login);
    }

    public boolean saveOrUpdate(User user) {
        long existingTtl = redis.ttl(user.login());
        AbstractTransaction transaction = redis.multi();
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

    @Override
    public void close() {
        redis.close();
    }
}
