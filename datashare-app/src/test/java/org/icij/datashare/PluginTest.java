package org.icij.datashare;

import org.junit.Test;

import java.net.URL;

import static org.fest.assertions.Assertions.assertThat;


public class PluginTest {
    @Test
    public void test_get_deliverable_url() throws Exception {
        Plugin plugin = new Plugin("id", "name", "1.0.0", "", new URL("https://normal/url/deliverable.tgz"));
        URL deliverableUrl = plugin.getDeliverableUrl();
        assertThat(deliverableUrl.toString()).isEqualTo("https://normal/url/deliverable.tgz");
    }

    @Test
    public void test_get_deliverable_url_with_github() throws Exception {
        assertThat(new Plugin("id", "name", "v1.2.3", "", new URL("https://github.com/foo/bar"))
                .getDeliverableUrl().toString())
                .isEqualTo("https://github.com/foo/bar/archive/v1.2.3.tar.gz");
    }

    @Test
    public void test_get_base_directory() throws Exception {
        Plugin plugin = new Plugin("id", "name", "1.0.0", "", new URL("https://normal/url/deliverable.tgz"));
        assertThat(plugin.getBaseDirectory().toString()).isEqualTo("id");
    }

    @Test
    public void test_get_base_directory_with_version() throws Exception {
        Plugin plugin = new Plugin("id", "name", "1.0.0", "", new URL("https://normal/url/deliverable-1.0.0.tgz"));
        assertThat(plugin.getBaseDirectory().toString()).isEqualTo("id-1.0.0");
    }

    @Test
    public void test_get_base_directory_with_version_starting_with_v() throws Exception {
        Plugin plugin = new Plugin("id", "name", "v1.0.0", "", new URL("https://normal/url/deliverable-1.0.0.tgz"));
        assertThat(plugin.getBaseDirectory().toString()).isEqualTo("id-1.0.0");
    }

    @Test
    public void test_get_base_directory_with_github_version_starting_with_v() throws Exception {
        assertThat(new Plugin("id", "name", "v1.2.3", "", new URL("https://github.com/foo/bar"))
                .getBaseDirectory().toString())
                .isEqualTo("id-1.2.3");
    }

    @Test
    public void test_get_base_directory_with_github_version() throws Exception {
        assertThat(new Plugin("id", "name", "1.2.3", "", new URL("https://github.com/foo/bar"))
                .getBaseDirectory().toString())
                .isEqualTo("id-1.2.3");
    }
}
