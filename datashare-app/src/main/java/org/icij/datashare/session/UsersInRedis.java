package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.security.User;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Hasher;
import org.icij.datashare.user.admin.UserFilter;
import org.icij.datashare.web.WebResponse;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.icij.datashare.user.User.fromJson;


public class UsersInRedis implements UserStore {
    private final JedisPool redis;

    @Inject
    public UsersInRedis(PropertiesProvider propertiesProvider) {
        redis = new JedisPool(propertiesProvider.get("redisAddress").orElse(EnvUtils.resolveUri("redis", "redis://redis:6379")));
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
    public WebResponse<org.icij.datashare.user.User> listUsers(UserFilter filter, Comparator<org.icij.datashare.user.User> sort, int from, int size) {
        try (Jedis jedis = redis.getResource()) {
            Set<String> logins = jedis.smembers("_datashare_users");
            if (logins.isEmpty()) {
                return new WebResponse<>(List.of(), from, size, 0);
            }
            List<String> jsons = jedis.mget(logins.toArray(new String[0]));
            Stream<org.icij.datashare.user.User> stream = parseUsers(jsons).filter(filter::matches);
            if (sort != null) stream = stream.sorted(sort);
            return WebResponse.fromStream(stream, from, size);
        }
    }

    @Override
    public List<org.icij.datashare.user.User> getUsersByIds(Set<String> ids) {
        if (ids.isEmpty()) return List.of();
        try (Jedis jedis = redis.getResource()) {
            List<String> jsons = jedis.mget(ids.toArray(new String[0]));
            return parseUsers(jsons).collect(Collectors.toList());
        }
    }

    private Stream<org.icij.datashare.user.User> parseUsers(List<String> jsons) {
        return jsons.stream()
                .filter(Objects::nonNull)
                .map(json -> fromJson(json))
                .filter(Objects::nonNull)
                .map(DatashareUser::new);
    }
}
