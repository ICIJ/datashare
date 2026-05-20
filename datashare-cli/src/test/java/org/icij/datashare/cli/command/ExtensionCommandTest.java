package org.icij.datashare.cli.command;

import org.junit.Test;

import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class ExtensionCommandTest extends AbstractDatashareCommandTest {

    @Test
    public void test_extension_list_no_filter() {
        Properties props = parse("extension", "list");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("extensionList", "true"));
    }

    @Test
    public void test_extension_list_with_filter() {
        Properties props = parse("extension", "list", ".*nlp.*");
        assertThat(props).includes(entry("extensionList", ".*nlp.*"));
    }

    @Test
    public void test_extension_install_by_id() {
        Properties props = parse("extension", "install", "my-ext");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("extensionInstall", "my-ext"));
    }

    @Test
    public void test_extension_install_by_url() {
        Properties props = parse("extension", "install", "https://example.com/ext.jar");
        assertThat(props).includes(entry("extensionInstall", "https://example.com/ext.jar"));
    }

    @Test
    public void test_extension_install_missing_id_fails() {
        int exitCode = parseExitCode("extension", "install");
        assertThat(exitCode).isNotEqualTo(0);
    }

    @Test
    public void test_extension_delete() {
        Properties props = parse("extension", "delete", "my-ext");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("extensionDelete", "my-ext"));
    }

    @Test
    public void test_extension_delete_missing_id_fails() {
        int exitCode = parseExitCode("extension", "delete");
        assertThat(exitCode).isNotEqualTo(0);
    }

    @Test
    public void test_extension_list_with_custom_extensions_dir() {
        Properties props = parse("--extensionsDir", "/custom/ext", "extension", "list");
        assertThat(props).includes(entry("extensionsDir", "/custom/ext"));
        assertThat(props).includes(entry("extensionList", "true"));
    }
}
