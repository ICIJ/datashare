package org.icij.datashare.asyncsearch;

import org.junit.Test;

import java.time.Duration;

import static org.fest.assertions.Assertions.assertThat;

public class EsDurationTest {
    private final Duration fallback = Duration.ofMinutes(5);

    @Test
    public void test_parse_seconds() {
        assertThat(EsDuration.parse("30s", fallback)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    public void test_parse_minutes() {
        assertThat(EsDuration.parse("10m", fallback)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    public void test_parse_hours_days_millis() {
        assertThat(EsDuration.parse("2h", fallback)).isEqualTo(Duration.ofHours(2));
        assertThat(EsDuration.parse("1d", fallback)).isEqualTo(Duration.ofDays(1));
        assertThat(EsDuration.parse("500ms", fallback)).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    public void test_null_or_blank_returns_fallback() {
        assertThat(EsDuration.parse(null, fallback)).isEqualTo(fallback);
        assertThat(EsDuration.parse("", fallback)).isEqualTo(fallback);
        assertThat(EsDuration.parse("   ", fallback)).isEqualTo(fallback);
    }

    @Test
    public void test_unparseable_returns_fallback() {
        assertThat(EsDuration.parse("banana", fallback)).isEqualTo(fallback);
        assertThat(EsDuration.parse("5", fallback)).isEqualTo(fallback);
    }

    @Test
    public void test_zero_amount_returns_fallback() {
        assertThat(EsDuration.parse("0m", fallback)).isEqualTo(fallback);
        assertThat(EsDuration.parse("0s", fallback)).isEqualTo(fallback);
    }
}
