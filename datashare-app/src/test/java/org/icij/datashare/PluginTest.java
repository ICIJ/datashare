package org.icij.datashare;

import org.junit.Test;

import java.net.URL;

import static org.fest.assertions.Assertions.assertThat;

public class PluginTest {

    @Test
    public void test_id_from_url() throws Exception {
        assertThat(new Plugin(new URL("http://foo.com/bar")).id).isEqualTo("bar");
        assertThat(new Plugin(new URL("http://foo.com/bar")).version).isNull();
        assertThat(new Plugin(new URL("file:///tmp/foo")).id).isEqualTo("foo");
    }

    @Test
    public void test_id_from_url_with_version_number() throws Exception {
        assertThat(new Extension(new URL("http://foo.com/bar-1.1.1")).id).isEqualTo("bar");
        assertThat(new Extension(new URL("http://foo.com/bar-1.1.1")).version).isEqualTo("1.1.1");
    }

    @Test
    public void test_id_from_url_with_trailling_slash() throws Exception {
        assertThat(new Extension(new URL("http://foo.com/bar/")).id).isEqualTo("bar");
        assertThat(new Extension(new URL("http://foo.com/bar/")).version).isNull();
    }

    @Test
    public void test_get_base_directory() throws Exception {
        Plugin plugin = new Plugin("id", "name", "1.0.0", "", new URL("https://normal/url/deliverable.tgz"),new URL("https://normal/url"));
        assertThat(plugin.getCanonicalPath().toString()).isEqualTo("id");
    }

    @Test
    public void test_get_base_directory_with_version() throws Exception {
        Plugin plugin = new Plugin("id", "name", "1.0.0", "", new URL("https://normal/url/deliverable-1.0.0.tgz"),new URL("https://normal/url/"));
        assertThat(plugin.getCanonicalPath().toString()).isEqualTo("id-1.0.0");
    }

    @Test
    public void test_get_base_directory_with_version_starting_with_v() throws Exception {
        Plugin plugin = new Plugin("id", "name", "v1.0.0", "", new URL("https://normal/url/deliverable-1.0.0.tgz"),new URL("https://normal/url/"));
        assertThat(plugin.getCanonicalPath().toString()).isEqualTo("id-1.0.0");
    }

    @Test
    public void test_get_base_directory_with_github_version_starting_with_v() throws Exception {
        assertThat(new Plugin("id", "name", "v1.2.3", "", new URL("https://github.com/foo/bar"),new URL("https://github.com/foo"))
                .getCanonicalPath().toString())
                .isEqualTo("id-1.2.3");
    }

    @Test
    public void test_get_base_directory_with_github_version() throws Exception {
        assertThat(new Plugin("id", "name", "1.2.3", "", new URL("https://github.com/foo/bar"),new URL("https://github.com/foo"))
                .getCanonicalPath().toString())
                .isEqualTo("id-1.2.3");
    }
}
