package org.icij.datashare.cli;

import org.junit.Test;
import picocli.CommandLine.TypeConversionException;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

public class AuthModeTest {
    @Test
    public void test_from_string_exact_cli_names() {
        assertThat(AuthMode.fromString("oauth")).isEqualTo(AuthMode.OAUTH);
        assertThat(AuthMode.fromString("form")).isEqualTo(AuthMode.FORM);
        assertThat(AuthMode.fromString("basic")).isEqualTo(AuthMode.BASIC);
        assertThat(AuthMode.fromString("yesCookie")).isEqualTo(AuthMode.YES_COOKIE);
        assertThat(AuthMode.fromString("yesBasic")).isEqualTo(AuthMode.YES_BASIC);
    }

    @Test
    public void test_from_string_is_case_and_separator_insensitive() {
        assertThat(AuthMode.fromString("OAUTH")).isEqualTo(AuthMode.OAUTH);
        assertThat(AuthMode.fromString("yes-cookie")).isEqualTo(AuthMode.YES_COOKIE);
        assertThat(AuthMode.fromString("YES_COOKIE")).isEqualTo(AuthMode.YES_COOKIE);
        assertThat(AuthMode.fromString("yescookie")).isEqualTo(AuthMode.YES_COOKIE);
    }

    @Test
    public void test_from_string_unknown_throws() {
        assertThrows(IllegalArgumentException.class, () -> AuthMode.fromString("ldap"));
    }

    @Test
    public void test_from_string_null_throws() {
        assertThrows(IllegalArgumentException.class, () -> AuthMode.fromString(null));
    }

    @Test
    public void test_to_string_returns_cli_name() {
        assertThat(AuthMode.YES_COOKIE.toString()).isEqualTo("yesCookie");
    }

    @Test
    public void test_picocli_converter_success() {
        assertThat(new AuthMode.PicocliConverter().convert("form")).isEqualTo(AuthMode.FORM);
    }

    @Test
    public void test_picocli_converter_unknown_throws_type_conversion() {
        assertThrows(TypeConversionException.class, () -> new AuthMode.PicocliConverter().convert("nope"));
    }
}
