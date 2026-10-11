package org.icij.datashare.text;

import org.junit.Test;

import java.util.Optional;

import static org.fest.assertions.Assertions.assertThat;

public class LanguageTest {
    @Test
    public void test_find_matches_iso_codes_and_names() {
        assertThat(Language.find("en")).isEqualTo(Optional.of(Language.ENGLISH));
        assertThat(Language.find("eng")).isEqualTo(Optional.of(Language.ENGLISH));
        assertThat(Language.find("english")).isEqualTo(Optional.of(Language.ENGLISH));
        assertThat(Language.find("ukn")).isEqualTo(Optional.of(Language.UNKNOWN));
    }

    @Test
    public void test_find_returns_empty_on_unknown_code() {
        assertThat(Language.find("chi_sim")).isEqualTo(Optional.empty());
        assertThat(Language.find("")).isEqualTo(Optional.empty());
        assertThat(Language.find(null)).isEqualTo(Optional.empty());
    }

    @Test
    public void test_parse_falls_back_to_unknown() {
        assertThat(Language.parse("chi_sim")).isEqualTo(Language.UNKNOWN);
    }
}
