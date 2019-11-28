package org.icij.datashare.session;

import org.icij.datashare.PropertiesProvider;
import org.junit.Test;

import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;


public class RedisUsersTest {
    RedisUsers users = new RedisUsers(new PropertiesProvider(new HashMap<String, String>() {{
        put("messageBusAddress", "redis");
    }}));

    @Test
    public void test_get_user_with_password() {
        users.createUser(new HashMapUser(new HashMap<String, String>(){{
            put("uid", "test");
            put("password", "test");
        }}));
        assertThat(users.find("test", "bad-pass")).isNull();
        assertThat(users.find("test", "test")).isNotNull();
    }
}
