package org.icij.datashare;


import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;

public class PluginServiceTest {
    @Rule public TemporaryFolder appFolder = new TemporaryFolder();

    @Test
    public void test_get_plugin_url() throws Exception {
        appFolder.newFolder("target_dir", "my_plugin").toPath().resolve("index.js").toFile().createNewFile();
        assertThat(new PluginService().getPluginUrl(appFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin"))).
                isEqualTo("/plugins/my_plugin/index.js");
    }

    @Test
    public void test_get_plugin_url_with_subdirectory() throws Exception {
        appFolder.newFolder("target_dir", "my_plugin", "dist").toPath().resolve("main.js").toFile().createNewFile();
        Path packageJson = appFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin").resolve("package.json");
        Files.write(packageJson, asList("{", "\"main\":\"dist/main.js\"", "}"));

        assertThat(new PluginService().getPluginUrl(appFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin"))).
                isEqualTo("/plugins/my_plugin/dist/main.js");
    }
}
