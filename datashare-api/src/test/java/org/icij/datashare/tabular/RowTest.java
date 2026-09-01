package org.icij.datashare.tabular;

import org.junit.Test;

import java.util.List;

import static org.fest.assertions.Assertions.assertThat;

public class RowTest {

    @Test
    public void test_a_header_carrying_a_non_breaking_space_reads_like_its_plain_form() {
        assertThat(Row.headers(List.of("full_name\u00A0"))).containsExactly("full_name");
    }

    @Test
    public void test_a_zero_width_character_in_a_header_is_removed_rather_than_kept() {
        assertThat(Row.headers(List.of("\uFEFFfull_name"))).containsExactly("full_name");
    }

    @Test
    public void test_clean_removes_a_zero_width_space_inside_a_value() {
        assertThat(Row.clean("AB\u200B123")).isEqualTo("AB123");
    }
}
