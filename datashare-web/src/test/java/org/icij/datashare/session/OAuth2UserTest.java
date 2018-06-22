package org.icij.datashare.session;

import org.junit.Test;

import java.io.IOException;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.session.OAuth2User.fromJson;

public class OAuth2UserTest {
    @Test
    public void test_ignore_null_values() throws IOException {
        assertThat(fromJson("{\"not_null\":\"value\",\"null\":null}").userMap.keySet()).containsOnly("not_null");
    }

    @Test
    public void test_stringify_arrays() throws IOException {
        assertThat(fromJson("{\"key\":\"value\",\"array\":[]}").userMap).includes(entry("key", "value"), entry("array", "[]"));
        assertThat(fromJson("{\"key\":\"value\",\"array\":[\"item\"]}").userMap).includes(entry("array", "[\"item\"]"));
    }
}