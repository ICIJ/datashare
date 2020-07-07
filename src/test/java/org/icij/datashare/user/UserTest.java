package org.icij.datashare.user;


import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.user.User.fromJson;

public class UserTest {
    @Test
    public void test_stringify_arrays()  {
        assertThat(fromJson("{\"key\":\"value\",\"array\":[]}", "test").details).includes(entry("key", "value"), entry("array", new LinkedList<>()));
        assertThat(fromJson("{\"key\":\"value\",\"array\":[\"item\"]}", "test").details).includes(entry("array", Collections.singletonList("item")));
    }

    @Test
    public void test_from_json_provider() {
        User user = fromJson("{\"uid\": \"user_id\"}", "test");
        assertThat(user.provider).isEqualTo("test");
        assertThat(user.id).isEqualTo("user_id");
    }

    @Test
    public void test_from_json_null() {
        assertThat(fromJson(null, "test")).isNull();
    }

    @Test
    public void test_getMap_with_null_value() {
        assertThat(fromJson("{\"key\":null,\"array\":[]}", "test").getDetails()).isNotEmpty();
    }

    @Test
    public void test_equals_with_user_subclass() {
        assertThat(User.local()).isEqualTo(User.local());
        assertThat(User.local()).isEqualTo(User.local());
    }

    @Test
    public void test_get_map_filters_password() {
        assertThat(new User(new HashMap<String, Object>() {{
            put("uid", "userid");
            put("password", "secret");
        }}).getDetails()).excludes(entry("password", "secret"));
    }

    @Test
    public void test_get_indices_for_local_user() {
        assertThat(User.local().getProjects()).containsExactly("local-datashare");
        assertThat(User.localUser("foo").getProjects()).containsExactly("foo-datashare");
    }

    @Test
    public void test_get_indices_for_external_user() {
        assertThat(new User(new HashMap<String, Object>() {{
            put("uid", "userid");
            put("datashare_projects", Collections.singletonList("external_index"));
        }}).getProjects()).containsExactly("external_index");
    }
}
