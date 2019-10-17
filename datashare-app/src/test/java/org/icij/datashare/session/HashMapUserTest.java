package org.icij.datashare.session;

import org.icij.datashare.user.User;
import org.junit.Test;

import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.session.HashMapUser.fromJson;

public class HashMapUserTest {
    @Test
    public void test_ignore_null_values() {
        assertThat(fromJson("{\"not_null\":\"value\",\"null\":null}").userMap.keySet()).containsOnly("not_null");
    }

    @Test
    public void test_stringify_arrays()  {
        assertThat(fromJson("{\"key\":\"value\",\"array\":[]}").userMap).includes(entry("key", "value"), entry("array", "[]"));
        assertThat(fromJson("{\"key\":\"value\",\"array\":[\"item\"]}").userMap).includes(entry("array", "[\"item\"]"));
    }

    @Test
    public void test_equals_with_user_subclass() {
        assertThat(User.local()).isEqualTo(HashMapUser.local());
        assertThat(HashMapUser.local()).isEqualTo(User.local());
    }

    @Test
    public void test_get_indices() {
        assertThat(HashMapUser.local().getProjects()).isEmpty();
        assertThat(new HashMapUser(new HashMap<String, String>() {{
            put("uid", "userid");
            put("datashare_indices", "[\"external_index\"]");
        }}).getProjects()).containsExactly("external_index");
    }
}
