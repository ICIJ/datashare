package org.icij.datashare.cli;

import joptsimple.OptionException;
import org.junit.Rule;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;

import java.io.IOException;
import java.nio.file.Path;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class DatashareCliTest {
    private DatashareCli cli = new DatashareCli();
    @Rule
    public final ExpectedSystemExit exit = ExpectedSystemExit.none();

    @Before
    public void setUp() {
        System.setProperty("user.home", "/home/datashare");
    }

    @After
    public void tearDown() {
        System.clearProperty("user.home");
    }

    @Test
    public void test_web_opt() {
        assertThat(cli.parseArguments(new String[] {"-o true"})).isNotNull();
        assertThat(cli.isWebServer()).isTrue();
        assertThat(cli.parseArguments(new String[] {"--mode=TASK_WORKER"})).isNotNull();
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
    public void test_port_opt() {
        cli.parseArguments(new String[] {"--port=7777"});
        assertThat(cli.properties).includes(entry("tcpListenPort", "7777"));
        assertThat(cli.properties).excludes(entry("port", "7777"));
    }

    @Test
    public void test_tcp_listen_port_opt() {
        cli.parseArguments(new String[] {"--tcpListenPort=7777"});
        assertThat(cli.properties).includes(entry("tcpListenPort", "7777"));
    }

    @Test
    public void test_mode_opt() {
        cli.parseArguments(new String[] {""});
        assertThat(cli.properties).includes(entry("mode", "LOCAL"));

        cli.parseArguments(new String[] {"--mode=SERVER"});
        assertThat(cli.properties).includes(entry("mode", "SERVER"));
    }

    @Test
    public void test_stages_opt() {
        cli.parseArguments(new String[] {"--stages=SCAN,INDEX,NLP"});
        assertThat(cli.properties).includes(entry("stages", "SCAN,INDEX,NLP"));
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
    public void test_relative_batch_download_dir() {
        cli.parseArguments(new String[] {"--batchDownloadDir", "foo"});
        Path userDir = Path.of(System.getProperty("user.dir"));
        assertThat(cli.properties).includes(entry("batchDownloadDir", userDir.resolve("foo").toString()));
    }

    @Test
    public void test_user_projects_key() {
        cli.parseArguments(new String[] {"--oauthUserProjectsAttribute", "foo.bar.baz"});
        assertThat(System.getProperty("datashare.user.projects")).isEqualTo("foo.bar.baz");
    }

    @Test
    public void test_absolute_batch_download_dir() {
        cli.parseArguments(new String[] {"--batchDownloadDir", "/home/foo"});
        assertThat(cli.properties).includes(entry("batchDownloadDir", "/home/foo"));
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

    @Test
    public void test_foo_extension_loaded_help() {
        cli.parseArguments(new String[] {"-s", "someSettingsPath", "--ext", "foo", "--fooCommand"});
        assertThat(cli.properties).includes(entry("settings", "someSettingsPath"));
    }

    @Test
    public void test_foo_extension_should_exit_with_3_for_unknown_extension_id() {
        exit.expectSystemExitWithStatus(3);
        cli.parseArguments(new String[] {"--ext", "bar"});
        assertThat(cli.properties).excludes(entry("defaultProject", "local-datashare"));
    }

    @Test
    public void test_data_dir_is_based_on_current_user_dir() {
        cli.parseArguments(new String[] {});
        assertThat(cli.properties).includes(entry("dataDir", "/home/datashare/Datashare"));
    }

    @Test
    public void test_elasticsearch_data_path_is_based_on_current_user_dir() {
        cli.parseArguments(new String[] {});
        assertThat(cli.properties).includes(entry("elasticsearchDataPath", "/home/datashare/.local/share/datashare/es"));
    }

    @Test
    public void test_extensions_dir_is_based_on_current_user_dir() {
        cli.parseArguments(new String[] {});
        assertThat(cli.properties).includes(entry("extensionsDir", "/home/datashare/.local/share/datashare/extensions"));
    }

    @Test
    public void test_plugins_dir_is_based_on_current_user_dir() {
        cli.parseArguments(new String[] {});
        assertThat(cli.properties).includes(entry("pluginsDir", "/home/datashare/.local/share/datashare/plugins"));
    }
}
