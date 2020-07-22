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
}
