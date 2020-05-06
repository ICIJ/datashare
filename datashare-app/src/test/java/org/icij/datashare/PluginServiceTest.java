package org.icij.datashare;


import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

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

    @Test
    public void test_project_filter_json_without_private() throws IOException {
        appFolder.newFolder("target_dir", "my_plugin").toPath().resolve("package.json").toFile().createNewFile();
        Path packageJson = appFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin");
        Files.write(packageJson.resolve("package.json"), asList("{", "\"main\":\"dist/main.js\"", "}"));
        assertThat(new PluginService().projectFilter(packageJson, asList("Toto", "Tata")).toString()).isEqualTo(packageJson.toString());
    }

    @Test
    public void test_project_filter_json_with_private_false() throws IOException {
        appFolder.newFolder("target_dir", "my_plugin").toPath().resolve("package.json").toFile().createNewFile();
        Path packageJson = appFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin");
        Files.write(packageJson.resolve("package.json"),asList(
                "{",
                "  \"private\": \"false\"",
                "}"
        ));
        assertThat(new PluginService().projectFilter(packageJson,asList("Toto","Tata")).toString()).isEqualTo(packageJson.toString());
    }

    @Test
    public void test_project_filter_json_with_private_true_without_projects() throws IOException {
        appFolder.newFolder("target_dir", "my_plugin").toPath().resolve("package.json").toFile().createNewFile();
        Path packageJson = appFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin");
        Files.write(packageJson.resolve("package.json"),asList(
                "{",
                "  \"private\": \"true\"",
                "}"
        ));
        assertThat(new PluginService().projectFilter(packageJson,asList("Toto","Tata"))).isNull();
    }

    @Test
    public void test_project_filter_json_with_private_true_with_projects() throws IOException {
        appFolder.newFolder("target_dir", "my_plugin").toPath().resolve("package.json").toFile().createNewFile();
        Path packageJson = appFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin");
        Files.write(packageJson.resolve("package.json"),asList(
                "{",
                "  \"private\": \"true\",",
                "  \"datashare\": {",
                "    \"projects\": [\"Titi\", \"Tata\"]",
                "   }",
                "}"
        ));
        assertThat(new PluginService().projectFilter(packageJson,asList("Tata")).toString()).isEqualTo(packageJson.toString());
    }
}
