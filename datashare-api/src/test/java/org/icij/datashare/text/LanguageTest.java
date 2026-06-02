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

    @Test public void test_parse_unlisted_language_is_unknown_not_exception() {
        assertThat(Language.parse("ast")).isEqualTo(Language.UNKNOWN);
        assertThat(Language.parse("xyz")).isEqualTo(Language.UNKNOWN);
    }
}
