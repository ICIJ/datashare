package org.icij.datashare;


import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
                "  \"private\": false",
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
                "  \"private\": true",
                "}"
        ));
        assertThat(new PluginService().projectFilter(packageJson,asList("Toto","Tata"))).isNull();
    }

    @Test
    public void test_project_filter_json_with_private_true_with_projects_ok() throws IOException {
        appFolder.newFolder("target_dir", "my_plugin").toPath().resolve("package.json").toFile().createNewFile();
        Path packageJson = appFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin");
        Files.write(packageJson.resolve("package.json"),asList(
                "{",
                "  \"private\": true,",
                "  \"datashare\": {",
                "    \"projects\": [\"Titi\", \"Tata\"]",
                "   }",
                "}"
        ));
        assertThat(new PluginService().projectFilter(packageJson,asList("Tata")).toString()).isEqualTo(packageJson.toString());
    }

    @Test
    public void test_project_filter_json_with_private_true_with_projects_nok() throws IOException {
        appFolder.newFolder("target_dir", "my_plugin").toPath().resolve("package.json").toFile().createNewFile();
        Path packageJson = appFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin");
        Files.write(packageJson.resolve("package.json"),asList(
                "{",
                "  \"private\": true,",
                "  \"datashare\": {",
                "    \"projects\": [\"Titi\", \"Tata\"]",
                "   }",
                "}"
        ));
        assertThat(new PluginService().projectFilter(packageJson,asList("Toto"))).isNull();
    }

    @Test
    public void test_list_plugins_from_plugins_json_file() throws Exception {
        List<Plugin> plugins = new PluginService().list();

        assertThat(plugins).hasSize(3);
        assertThat(plugins.get(0).id).isEqualTo("my-plugin-foo");
        assertThat(plugins.get(0).description).isEqualTo("description for foo");
        assertThat(plugins.get(0).name).isEqualTo("Foo Plugin");
        assertThat(plugins.get(0).url).isEqualTo(new URL("https://github.com/ICIJ/mypluginfoo/releases/my-plugin-foo.tgz"));
    }

    @Test
    public void test_list_plugins_from_plugins_json_file_with_pattern() throws Exception {
        PluginService pluginService = new PluginService();
        assertThat(pluginService.list(".*")).hasSize(3);
        assertThat(pluginService.list(".*foo.*")).hasSize(1);
        assertThat(pluginService.list(".*baz.*")).hasSize(1);
        assertThat(pluginService.list(".*baz.*").get(0).id).isEqualTo("my-plugin-baz");
        assertThat(pluginService.list(".*ba.*")).hasSize(2);
    }
}
