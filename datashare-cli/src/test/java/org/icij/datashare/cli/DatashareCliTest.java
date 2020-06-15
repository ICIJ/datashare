package org.icij.datashare.cli;

import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;

import java.io.IOException;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class DatashareCliTest {
    private DatashareCli cli = new DatashareCli();
    @Rule public final ExpectedSystemExit exit = ExpectedSystemExit.none();

    @Test
    public void test_web_opt() {
        assertThat(cli.parseArguments(new String[] {"-o true"})).isNotNull();
        assertThat(cli.isWebServer()).isTrue();

        assertThat(cli.parseArguments(new String[] {"--mode=BATCH"})).isNotNull();
        assertThat(cli.isWebServer()).isFalse();
        assertThat(cli.parseArguments(new String[] {"--mode=CLI"})).isNotNull();
        assertThat(cli.isWebServer()).isFalse();
    }

    @Test
    public void test_override_opt_last_option_wins() {
        cli.parseArguments(new String[] {"--mode=SERVER", "--mode=LOCAL"});
        assertThat(cli.properties).includes(entry("mode", "LOCAL"));
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

    @Test
    public void test_get_version() throws IOException {
        assertThat(cli.getVersion()).isEqualTo("7.0.2");
    }

    @Test
    public void test_batch_daemon() {
        assertThat(cli.parseArguments(new String[] {""}).isBatchDaemon()).isFalse();
        assertThat(cli.parseArguments(new String[] {"--mode=BATCH"}).isBatchDaemon()).isTrue();
    }
}
