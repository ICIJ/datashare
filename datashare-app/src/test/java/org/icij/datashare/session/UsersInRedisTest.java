package org.icij.datashare.session;

import org.icij.datashare.PropertiesProvider;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;


public class UsersInRedisTest {
    UsersInRedis users = new UsersInRedis(new PropertiesProvider(new HashMap<String, String>() {{
        put("messageBusAddress", "redis");
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
}
