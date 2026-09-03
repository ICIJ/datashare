package org.icij.datashare.tabular;

import org.junit.Test;

import java.util.List;
import java.util.Map;

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

    @Test
    public void test_clean_keeps_a_zero_width_non_joiner_that_spells_a_different_word() {
        assertThat(Row.clean("\u0645\u06CC\u200C\u0631\u0648\u062F"))
                .isEqualTo("\u0645\u06CC\u200C\u0631\u0648\u062F");
    }

    @Test
    public void test_clean_keeps_the_zero_width_joiner_that_holds_an_emoji_together() {
        assertThat(Row.clean("\uD83D\uDC69\u200D\uD83D\uDCBB"))
                .isEqualTo("\uD83D\uDC69\u200D\uD83D\uDCBB");
    }

    @Test
    public void test_clean_removes_the_direction_marks_a_spreadsheet_wraps_around_a_value() {
        assertThat(Row.clean("\u200E12\u200F")).isEqualTo("12");
    }

    @Test
    public void test_clean_removes_a_word_joiner() {
        assertThat(Row.clean("AB\u2060123")).isEqualTo("AB123");
    }

    @Test
    public void test_clean_removes_a_soft_hyphen() {
        assertThat(Row.clean("AB\u00AD123")).isEqualTo("AB123");
    }

    @Test
    public void test_a_surplus_cell_holding_only_a_non_breaking_space_carries_no_data() {
        assertThat(Row.values(List.of("a"), List.of("1", "\u00A0"), 2L)).isEqualTo(Map.of("a", "1"));
    }
}
