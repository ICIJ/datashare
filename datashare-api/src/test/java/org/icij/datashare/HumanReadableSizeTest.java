package org.icij.datashare;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;


public class HumanReadableSizeTest {
    @Test
    public void test_parse_bytes() {
        assertThat(HumanReadableSize.parse("12")).isEqualTo(12);
        assertThat(HumanReadableSize.parse("1024")).isEqualTo(1024);
    }

    @Test
    public void test_parse_kilobytes() {
        assertThat(HumanReadableSize.parse("1K")).isEqualTo(1024);
        assertThat(HumanReadableSize.parse("1024K")).isEqualTo(1024 * 1024);
    }

    @Test
    public void test_parse_megabytes() {
        assertThat(HumanReadableSize.parse("1M")).isEqualTo(1024 * 1024);
    }

    @Test
    public void test_parse_gigabytes() {
        assertThat(HumanReadableSize.parse("1G")).isEqualTo(1024 * 1024 * 1024);
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_syntax_error() {
        HumanReadableSize.parse("bad");
    }
}