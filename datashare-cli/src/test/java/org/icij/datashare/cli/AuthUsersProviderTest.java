package org.icij.datashare.cli;

import org.junit.Test;

import java.util.Optional;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

public class AuthUsersProviderTest {
    @Test
    public void test_from_string_exact_cli_names() {
        assertThat(AuthUsersProvider.fromString("database")).isEqualTo(AuthUsersProvider.DATABASE);
        assertThat(AuthUsersProvider.fromString("redis")).isEqualTo(AuthUsersProvider.REDIS);
    }

    @Test
    public void test_from_string_is_case_and_separator_insensitive() {
        assertThat(AuthUsersProvider.fromString("DATABASE")).isEqualTo(AuthUsersProvider.DATABASE);
        assertThat(AuthUsersProvider.fromString("Redis")).isEqualTo(AuthUsersProvider.REDIS);
    }

    @Test
    public void test_from_string_unknown_throws() {
        assertThrows(IllegalArgumentException.class, () -> AuthUsersProvider.fromString("ldap"));
    }

    @Test
    public void test_from_string_null_throws() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AuthUsersProvider.fromString(null));
        assertThat(e.getMessage()).isEqualTo("Auth users provider must not be null");
    }

    @Test
    public void test_try_from_string_known_label_present() {
        assertThat(AuthUsersProvider.tryFromString("redis")).isEqualTo(Optional.of(AuthUsersProvider.REDIS));
    }

    @Test
    public void test_try_from_string_unknown_is_empty() {
        // A fully-qualified class name is not a label: the resolver must fall through to Class.forName.
        assertThat(AuthUsersProvider.tryFromString("org.icij.datashare.session.UsersInDb")).isEqualTo(Optional.empty());
    }

    @Test
    public void test_try_from_string_null_is_empty() {
        assertThat(AuthUsersProvider.tryFromString(null)).isEqualTo(Optional.empty());
    }

    @Test
    public void test_to_string_returns_cli_name() {
        assertThat(AuthUsersProvider.DATABASE.toString()).isEqualTo("database");
    }
}
