package org.icij.datashare.cli;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class DatashareCliTest {
    private DatashareCli cli = new DatashareCli();
    @Test
    public void test_web_opt() {
        assertThat(cli.parseArguments(new String[] {"-o"})).isTrue();
        assertThat(cli.webServer).isTrue();

        assertThat(cli.parseArguments(new String[] {"--noweb"})).isTrue();
        assertThat(cli.webServer).isFalse();
    }

    @Test
    public void test_mode_opt() {
        cli.parseArguments(new String[] {""});
        assertThat(cli.properties).includes(entry("mode", "LOCAL"));

        cli.parseArguments(new String[] {"--mode=SERVER"});
        assertThat(cli.properties).includes(entry("mode", "SERVER"));
    }

    @Test
    public void test_option_not_specified() {
        cli.parseArguments(new String[] {""});

        assertThat(cli.properties).excludes(entry("oauthClientId", "false"));
    }
}
