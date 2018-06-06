package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.security.User;
import net.codestory.http.security.Users;
import org.icij.datashare.PropertiesProvider;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;

import static java.util.Optional.ofNullable;

public class RedisUsers implements Users {
    private final Jedis redis;
    private final Integer ttl;

    @Inject
    public RedisUsers(PropertiesProvider propertiesProvider) {
        this.redis = new Jedis(propertiesProvider.getProperties().getProperty("messageBusAddress"));
        this.ttl = Integer.valueOf(ofNullable(propertiesProvider.getProperties().getProperty("sessionTtlSeconds")).orElse("1"));
    }

    @Override
    public User find(String login, String password) {
        return null;
    }

    @Override
    public User find(String login) {
        return getUser(login);
    }

    void createUser(OAuth2User user) {
        Transaction transaction = this.redis.multi();
        transaction.hmset(user.login(), user.userMap);
        transaction.expire(user.login(), this.ttl);
        transaction.exec();
    }

    OAuth2User getUser(String login) {
        return new OAuth2User(this.redis.hgetAll(login));
    }

    void removeUser(String login) {
        this.redis.del(login);
    }
}
