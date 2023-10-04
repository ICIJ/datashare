package org.icij.datashare.cli;

import joptsimple.OptionException;
import joptsimple.OptionSet;
import org.junit.Test;

import java.io.IOException;
import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class DatashareCliTest {
    private DatashareCli cli = new DatashareCli();

    @Test
    public void test_web_opt() {
        assertThat(cli.parseArguments(new String[] {"-o true"})).isNotNull();
        assertThat(cli.isWebServer()).isTrue();
        assertThat(cli.parseArguments(new String[] {"--mode=BATCH_SEARCH"})).isNotNull();
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
    public void test_option_list_plugins() {
        cli.parseArguments(new String[] {"--pluginList"});
        assertThat(cli.properties).includes(entry("pluginList", "true"));

        cli.parseArguments(new String[] {"--pluginList=.*"});
        assertThat(cli.properties).includes(entry("pluginList", ".*"));

        cli.parseArguments(new String[] {""});
        assertThat(cli.properties).excludes(entry("pluginList", "unused"));
    }

    @Test
    public void test_get_version() throws IOException {
        assertThat(cli.getVersion()).isEqualTo("7.0.2");
    }

    @Test
    public void test_max_batch_download_size() {
        cli.parseArguments(new String[] {"--batchDownloadMaxSize", "123"});
        assertThat(cli.properties).includes(entry("batchDownloadMaxSize", "123"));

        cli.parseArguments(new String[] {"--batchDownloadMaxSize", "123G"});
        assertThat(cli.properties).includes(entry("batchDownloadMaxSize", "123G"));
    }

    @Test(expected = OptionException.class)
    public void test_max_batch_download_size_illegal_value() {
        cli.asProperties(cli.createParser().parse("--embeddedDocumentDownloadMaxSize", "123A"), null);
    }

    @Test
    public void test_embedded_document_download_max_size() {
        cli.parseArguments(new String[] {"--embeddedDocumentDownloadMaxSize", "123"});
        assertThat(cli.properties).includes(entry("embeddedDocumentDownloadMaxSize", "123"));

        cli.parseArguments(new String[] {"--embeddedDocumentDownloadMaxSize", "123G"});
        assertThat(cli.properties).includes(entry("embeddedDocumentDownloadMaxSize", "123G"));
    }

    @Test(expected = OptionException.class)
    public void test_embedded_document_download_max_size_illegal_value() {
        cli.asProperties(cli.createParser().parse("--batchDownloadMaxSize", "123A"), null);
    }

    @Test
    public void test_no_default_indexing_language_value() {
        cli.parseArguments(new String[] {""});
        assertThat(cli.properties).excludes(entry("language", null));
    }

    @Test
    public void test_has_english_indexing_language_value() {
        cli.parseArguments(new String[] {"--language", "ENGLISH"});
        assertThat(cli.properties).includes(entry("language", "ENGLISH"));
    }

    @Test
    public void test_digest_project_name_should_be_the_same_as_default_project() {
        cli.parseArguments(new String[] {});
        assertThat(cli.properties).includes(entry("defaultProject", "local-datashare"));
        assertThat(cli.properties).includes(entry("digestProjectName", "local-datashare"));
    }

    @Test
    public void test_digest_project_name_should_be_emptied_if_no_digest_project_flag() {
        cli.parseArguments(new String[] {"--noDigestProject", "true"});
        assertThat(cli.properties).includes(entry("defaultProject", "local-datashare"));
        assertThat(cli.properties).includes(entry("noDigestProject", "true"));
        assertThat(cli.properties.getProperty("digestProjectName")).isNull();
    }

    @Test
    public void test_digest_project_name_should_be_left_as_is_if_provided() {
        cli.parseArguments(new String[] {"--digestProjectName", "foo"});
        assertThat(cli.properties).includes(entry("defaultProject", "local-datashare"));
        assertThat(cli.properties).includes(entry("digestProjectName", "foo"));
        assertThat(cli.properties).includes(entry("noDigestProject", "false"));
    }

    @Test
    public void test_foo_extension_loaded() {
        cli.parseArguments(new String[] {"--foo", "bar"});
        assertThat(cli.properties).includes(entry("foo", "bar"));
    }
}
