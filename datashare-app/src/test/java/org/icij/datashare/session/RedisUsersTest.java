package org.icij.datashare.session;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Hasher;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;


public class RedisUsersTest {
    RedisUsers users = new RedisUsers(new PropertiesProvider(new HashMap<String, String>() {{
        put("messageBusAddress", "redis");
    }}));

    @Test
    public void test_get_user_with_password() {
        users.createUser(new DatashareUser(new HashMap<String, Object>(){{
            put("uid", "test");
            put("password", Hasher.SHA_256.hash("test"));
        }}));
        assertThat(users.find("test", "bad-pass")).isNull();
        assertThat(users.find("test", "test")).isNotNull();
    }

    @Test
    public void test_get_user_with_object() {
        users.createUser(new DatashareUser(new HashMap<String, Object>(){{
            put("uid", "test");
            put("password", Hasher.SHA_256.hash("test"));
            put("projects", asList("project_01", "project_02"));
            put("object", new HashMap<String, String>() {{ put("key", "value"); }});
        }}));
        DatashareUser user = (DatashareUser) users.find("test", "test");
        assertThat((List<String>) user.get("projects")).hasSize(2);
        assertThat((HashMap<String, String>) user.get("object")).hasSize(1);
    }
}
