package org.icij.datashare.cli;

import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;

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
    public void test_project_name_is_mutually_exclusive_with_user_name(){
        exit.expectSystemExit();
        assertThat(cli.parseArguments(new String[]{"-p projectName", "-u userName"}));
    }

    @Test
    public void test_user_name_sets_project_name(){
        assertThat(cli.parseArguments(new String[]{"-u user"})).isNotNull();
        assertThat(cli.properties).includes(entry("defaultProject", "user-datashare"));
    }
}
