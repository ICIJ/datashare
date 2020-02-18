package org.icij.datashare;


import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.fest.assertions.Assertions.assertThat;

public class PluginServiceTest {
    @Rule public TemporaryFolder appFolder = new TemporaryFolder();

    @Test
    public void test_get_plugin_url() throws Exception {
        appFolder.newFolder("target_dir", "my_plugin").toPath().resolve("index.js").toFile().createNewFile();
        assertThat(new PluginService().getPluginUrl(appFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin"))).
                isEqualTo("/plugins/my_plugin/index.js");
    }
}
