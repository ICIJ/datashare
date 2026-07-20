package org.icij.datashare.session;

import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Hasher;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.HashMap;
import java.util.List;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;


public class UsersIdProviderRedisCacheTest {
    static UsersIdProviderRedisCache users;

    @BeforeClass
    public static void createUsers() {
        users = new UsersIdProviderRedisCache(new PropertiesProvider(new HashMap<>() {{
            put("messageBusAddress", EnvUtils.resolveUri("redis", "redis://redis:6379"));
        }}));
    }

    @AfterClass
    public static void closePool() throws Exception {
        RedisTestUtils.closeRedisPool(users);
    }

    @Test
    public void test_get_user_with_password() {
        users.saveOrUpdate(new DatashareUser(new HashMap<>() {{
            put("uid", "test");
        }}));
        assertThat(users.find("test")).isNotNull();
    }

    @Test
    public void test_get_user_with_object() {
        users.saveOrUpdate(new DatashareUser(new HashMap<>() {{
            put("uid", "test");
            put("projects", asList("project_01", "project_02"));
            put("object", new HashMap<String, String>() {{
                put("key", "value");
            }});
        }}));
        DatashareUser user = (DatashareUser) users.find("test");
        assertThat((List<String>) user.get("projects")).hasSize(2);
        assertThat((HashMap<String, String>) user.get("object")).hasSize(1);
    }

    @Test
    public void find_user_in_db_with_password() {
        users.saveOrUpdate(new DatashareUser(new HashMap<>() {{
            put("uid", "foo");
            put("projects", asList("project_01", "project_02"));
            put("password", Hasher.SHA_256.hash("bar"));
        }}));
        DatashareUser user = (DatashareUser) users.find("foo", "bar");
        assertThat(user).isNotNull();
        assertThat(user.getId()).isEqualTo("foo");
    }

    @Test
    public void find_user_in_db_with_password_no_user() {
        assertThat(users.find("null", "null")).isNull();
    }

    @Test
    public void find_user_in_db_with_bad_password() {
        users.saveOrUpdate(new DatashareUser(new HashMap<String, Object>(){{
            put("uid", "foo");
            put("projects", asList("project_01", "project_02"));
            put("password", Hasher.SHA_256.hash("bar"));
        }}));
        assertThat(users.find("foo", "bad")).isNull();
    }

    @Test
    public void saveOrUpdate_preserves_persistent_entry_without_adding_ttl() {
        // Simulates a manually-provisioned Redis user (no TTL). The CLI's
        // project-grant path calls saveOrUpdate; it must not stamp an expiry
        // onto a persistent key, which would log the user out after 1 second.
        String redisAddress = EnvUtils.resolveUri("redis", "redis://redis:6379");
        try (JedisPool pool = new JedisPool(redisAddress); Jedis jedis = pool.getResource()) {
            jedis.set("persistent-user", "{\"uid\":\"persistent-user\",\"password\":\"hash\"}");
            // key has no TTL (-1)

            users.saveOrUpdate(new DatashareUser(new HashMap<String, Object>() {{
                put("uid", "persistent-user");
                put("groups_by_applications", new HashMap<String, Object>() {{
                    put("datashare", asList("project-a"));
                }});
            }}));

            assertThat(jedis.ttl("persistent-user")).isEqualTo(-1L);
            jedis.del("persistent-user");
        }
    }

    @Test
    public void saveOrUpdate_does_not_shorten_active_session_ttl() {
        // Simulates an active OAuth2 session (TTL >> 1s). The CLI's sessionTtlSeconds
        // defaults to 1; saveOrUpdate must not shorten the session, which would log
        // the user out immediately after a project-grant command.
        String redisAddress = EnvUtils.resolveUri("redis", "redis://redis:6379");
        try (JedisPool pool = new JedisPool(redisAddress); Jedis jedis = pool.getResource()) {
            jedis.setex("oauth-user", 600, "{\"uid\":\"oauth-user\"}");

            users.saveOrUpdate(new DatashareUser(new HashMap<String, Object>() {{
                put("uid", "oauth-user");
                put("groups_by_applications", new HashMap<String, Object>() {{
                    put("datashare", asList("project-a"));
                }});
            }}));

            long remainingTtl = jedis.ttl("oauth-user");
            assertThat(remainingTtl).isGreaterThan(1L);
            jedis.del("oauth-user");
        }
    }
}
