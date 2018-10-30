package org.icij.datashare.cli;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class DatashareCliTest {
    @Test
    public void test_web_opt() {
        assertThat(DatashareCli.parseArguments(new String[] {"-o"})).isTrue();
        assertThat(DatashareCli.webServer).isFalse();

        assertThat(DatashareCli.parseArguments(new String[] {"--web"})).isTrue();
        assertThat(DatashareCli.webServer).isTrue();

        assertThat(DatashareCli.parseArguments(new String[] {"-w"})).isTrue();
        assertThat(DatashareCli.webServer).isTrue();
    }

    @Test
    public void test_mode_opt() {
        DatashareCli.parseArguments(new String[] {""});
        assertThat(DatashareCli.properties).includes(entry("mode", "LOCAL"));

        DatashareCli.parseArguments(new String[] {"--mode=SERVER"});
        assertThat(DatashareCli.properties).includes(entry("mode", "SERVER"));
    }

    @Test
    public void test_option_not_specified() {
        DatashareCli.parseArguments(new String[] {""});

        assertThat(DatashareCli.properties).excludes(entry("oauthClientId", "false"));
    }
}
