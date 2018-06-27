package org.icij.datashare.session;

import org.icij.datashare.user.User;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.session.OAuth2User.fromJson;

public class OAuth2UserTest {
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
        assertThat(User.local()).isEqualTo(OAuth2User.local());
        assertThat(OAuth2User.local()).isEqualTo(User.local());
    }
}