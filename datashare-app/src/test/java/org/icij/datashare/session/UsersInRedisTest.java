package org.icij.datashare.session;

import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Hasher;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;


public class UsersInRedisTest {
    UsersInRedis users = new UsersInRedis(new PropertiesProvider(new HashMap<>() {{
        put("messageBusAddress", "redis://" + EnvUtils.resolveHost("redis") + ":6379");
    }}));

    @Test
    public void test_get_user_with_password() {
        users.saveOrUpdate(new DatashareUser(new HashMap<String, Object>(){{
            put("uid", "test");
        }}));
        assertThat(users.find("test")).isNotNull();
    }

    @Test
    public void test_get_user_with_object() {
        users.saveOrUpdate(new DatashareUser(new HashMap<String, Object>(){{
            put("uid", "test");
            put("projects", asList("project_01", "project_02"));
            put("object", new HashMap<String, String>() {{ put("key", "value"); }});
        }}));
        DatashareUser user = (DatashareUser) users.find("test");
        assertThat((List<String>) user.get("projects")).hasSize(2);
        assertThat((HashMap<String, String>) user.get("object")).hasSize(1);
    }

    @Test
    public void find_user_in_db_with_password() {
        users.saveOrUpdate(new DatashareUser(new HashMap<String, Object>(){{
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
}
