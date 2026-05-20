package org.icij.datashare.cli.command;

import org.junit.Test;

import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class PluginCommandTest extends AbstractDatashareCommandTest {

    @Test
    public void test_plugin_list_no_filter() {
        Properties props = parse("plugin", "list");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("pluginList", "true"));
    }

    @Test
    public void test_plugin_list_with_filter() {
        Properties props = parse("plugin", "list", ".*foo.*");
        assertThat(props).includes(entry("pluginList", ".*foo.*"));
    }

    @Test
    public void test_plugin_install_by_id() {
        Properties props = parse("plugin", "install", "my-plugin");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("pluginInstall", "my-plugin"));
    }

    @Test
    public void test_plugin_install_by_url() {
        Properties props = parse("plugin", "install", "https://example.com/plugin.jar");
        assertThat(props).includes(entry("pluginInstall", "https://example.com/plugin.jar"));
    }

    @Test
    public void test_plugin_install_by_path() {
        Properties props = parse("plugin", "install", "/opt/plugins/my-plugin.jar");
        assertThat(props).includes(entry("pluginInstall", "/opt/plugins/my-plugin.jar"));
    }

    @Test
    public void test_plugin_install_missing_id_fails() {
        int exitCode = parseExitCode("plugin", "install");
        assertThat(exitCode).isNotEqualTo(0);
    }

    @Test
    public void test_plugin_delete() {
        Properties props = parse("plugin", "delete", "my-plugin");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("pluginDelete", "my-plugin"));
    }

    @Test
    public void test_plugin_delete_missing_id_fails() {
        int exitCode = parseExitCode("plugin", "delete");
        assertThat(exitCode).isNotEqualTo(0);
    }

    @Test
    public void test_plugin_list_with_custom_plugins_dir() {
        Properties props = parse("--pluginsDir", "/custom/plugins", "plugin", "list");
        assertThat(props).includes(entry("pluginsDir", "/custom/plugins"));
        assertThat(props).includes(entry("pluginList", "true"));
    }
}
