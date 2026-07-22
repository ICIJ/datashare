package org.icij.datashare.session;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.security.User;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Hasher;
import org.icij.datashare.user.admin.UserFilter;
import org.icij.datashare.web.WebResponse;
import redis.clients.jedis.JedisPooled;

import java.io.Closeable;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.icij.datashare.user.User.fromJson;


@Singleton
public class UsersInRedis implements UserStore, Closeable {
    private final JedisPooled redis;

    @Inject
    public UsersInRedis(PropertiesProvider propertiesProvider) {
        redis = RedisPoolFactory.createPool(propertiesProvider);
    }

    @Override
    public User find(String login) {
        org.icij.datashare.user.User user = fromJson(redis.get(login));
        return user != null ? new DatashareUser(user) : null;
    }

    @Override
    public User find(String login, String password) {
        org.icij.datashare.user.User user = fromJson(redis.get(login));
        return user != null && Hasher.SHA_256.hash(password).equals(user.details.get("password")) ? new DatashareUser(user): null;
    }

    @Override
    public boolean save(org.icij.datashare.user.User user) {
        redis.set(user.id, JsonObjectMapper.serialize(user.details));
        redis.sadd("_datashare_users", user.id);
        return true;
    }

    @Override
    public boolean delete(String login) {
        long deleted = redis.del(login);
        if (deleted > 0) {
            redis.srem("_datashare_users", login);
        }
        return deleted > 0;
    }

    @Override
    public WebResponse<org.icij.datashare.user.User> listUsers(UserFilter filter, Comparator<org.icij.datashare.user.User> sort, int from, int size) {
        Set<String> logins = redis.smembers("_datashare_users");
        if (logins.isEmpty()) {
            return new WebResponse<>(List.of(), from, size, 0);
        }
        List<String> jsons = redis.mget(logins.toArray(new String[0]));
        Stream<org.icij.datashare.user.User> stream = parseUsers(jsons).filter(filter::matches);
        if (sort != null) stream = stream.sorted(sort);
        return WebResponse.fromStream(stream, from, size);
    }

    @Override
    public List<org.icij.datashare.user.User> getUsersByIds(Set<String> ids) {
        if (ids.isEmpty()) return List.of();
        List<String> jsons = redis.mget(ids.toArray(new String[0]));
        return parseUsers(jsons).collect(Collectors.toList());
    }

    private Stream<org.icij.datashare.user.User> parseUsers(List<String> jsons) {
        return jsons.stream()
                .filter(Objects::nonNull)
                .map(json -> fromJson(json))
                .filter(Objects::nonNull)
                .map(DatashareUser::new);
    }

    @Override
    public void close() {
        redis.close();
    }
}
