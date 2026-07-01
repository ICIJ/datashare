package org.icij.datashare;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;


public class HumanReadableDurationTest {
    @Test
    public void test_whole_days() {
        assertThat(HumanReadableDuration.fromHours(24)).isEqualTo("1 day");
        assertThat(HumanReadableDuration.fromHours(48)).isEqualTo("2 days");
        assertThat(HumanReadableDuration.fromHours(168)).isEqualTo("7 days");
    }

    @Test
    public void test_hours() {
        assertThat(HumanReadableDuration.fromHours(1)).isEqualTo("1 hour");
        assertThat(HumanReadableDuration.fromHours(36)).isEqualTo("36 hours");
    }
}
