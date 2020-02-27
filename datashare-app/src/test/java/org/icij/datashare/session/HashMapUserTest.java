package org.icij.datashare.session;

import org.icij.datashare.user.User;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.session.HashMapUser.fromJson;

public class HashMapUserTest {
    @Test
    public void test_stringify_arrays()  {
        assertThat(fromJson("{\"key\":\"value\",\"array\":[]}").userMap).includes(entry("key", "value"), entry("array", new LinkedList<>()));
        assertThat(fromJson("{\"key\":\"value\",\"array\":[\"item\"]}").userMap).includes(entry("array", Collections.singletonList("item")));
    }

    @Test
    public void test_from_json_null() {
        assertThat(fromJson(null)).isNull();
    }

    @Test
    public void test_getMap_with_null_value() {
        assertThat(fromJson("{\"key\":null,\"array\":[]}").getMap()).isNotEmpty();
    }

    @Test
    public void test_equals_with_user_subclass() {
        assertThat(User.local()).isEqualTo(HashMapUser.local());
        assertThat(HashMapUser.local()).isEqualTo(User.local());
    }

    @Test
    public void test_get_map_filters_password() {
        assertThat(new HashMapUser(new HashMap<String, Object>() {{
            put("uid", "userid");
            put("password", "secret");
        }}).getMap()).excludes(entry("password", "secret"));
    }

    @Test
    public void test_get_indices_for_local_user() {
        assertThat(HashMapUser.local().getProjects()).containsExactly("local-datashare");
        assertThat(HashMapUser.localUser("foo").getProjects()).containsExactly("foo-datashare");
    }

    @Test
    public void test_get_indices_for_external_user() {
        assertThat(new HashMapUser(new HashMap<String, Object>() {{
            put("uid", "userid");
            put("datashare_indices", Collections.singletonList("external_index"));
        }}).getProjects()).containsExactly("external_index");
    }
}
