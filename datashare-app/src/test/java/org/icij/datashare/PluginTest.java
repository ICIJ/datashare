package org.icij.datashare;

import org.junit.Test;

import java.net.URL;

import static org.fest.assertions.Assertions.assertThat;


public class PluginTest {
    @Test
    public void test_get_base_directory() throws Exception {
        Plugin plugin = new Plugin("id", "name", "1.0.0", "", new URL("https://normal/url/deliverable.tgz"));
        assertThat(plugin.getBasePath().toString()).isEqualTo("id");
    }

    @Test
    public void test_get_base_directory_with_version() throws Exception {
        Plugin plugin = new Plugin("id", "name", "1.0.0", "", new URL("https://normal/url/deliverable-1.0.0.tgz"));
        assertThat(plugin.getBasePath().toString()).isEqualTo("id-1.0.0");
    }

    @Test
    public void test_get_base_directory_with_version_starting_with_v() throws Exception {
        Plugin plugin = new Plugin("id", "name", "v1.0.0", "", new URL("https://normal/url/deliverable-1.0.0.tgz"));
        assertThat(plugin.getBasePath().toString()).isEqualTo("id-1.0.0");
    }

    @Test
    public void test_get_base_directory_with_github_version_starting_with_v() throws Exception {
        assertThat(new Plugin("id", "name", "v1.2.3", "", new URL("https://github.com/foo/bar"))
                .getBasePath().toString())
                .isEqualTo("id-1.2.3");
    }

    @Test
    public void test_get_base_directory_with_github_version() throws Exception {
        assertThat(new Plugin("id", "name", "1.2.3", "", new URL("https://github.com/foo/bar"))
                .getBasePath().toString())
                .isEqualTo("id-1.2.3");
    }
}
