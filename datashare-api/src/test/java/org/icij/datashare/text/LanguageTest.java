package org.icij.datashare.text;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class LanguageTest {
    @Test public void test_parse_known_iso6391() {
        assertThat(Language.parse("en")).isEqualTo(Language.ENGLISH);
    }

    @Test public void test_parse_known_iso6392() {
        assertThat(Language.parse("eng")).isEqualTo(Language.ENGLISH);
    }

    @Test public void test_parse_is_case_insensitive() {
        assertThat(Language.parse("EN")).isEqualTo(Language.ENGLISH);
    }

    @Test public void test_parse_null_or_empty_is_unknown() {
        assertThat(Language.parse(null)).isEqualTo(Language.UNKNOWN);
        assertThat(Language.parse("")).isEqualTo(Language.UNKNOWN);
    }

    @Test public void test_parse_resolves_enum_name_case_insensitively() {
        assertThat(Language.parse("Maltese")).isEqualTo(Language.MALTESE);
        assertThat(Language.parse("ENGLISH")).isEqualTo(Language.ENGLISH);
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_parse_unknown_code_throws() {
        Language.parse("foo");
    }
}
