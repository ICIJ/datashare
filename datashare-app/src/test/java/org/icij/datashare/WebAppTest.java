package org.icij.datashare;


import org.fest.assertions.Condition;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.PropertiesProvider.PLUGINS_DIR;

public class WebAppTest {
    @Rule public TemporaryFolder appFolder = new TemporaryFolder();
    private Path pluginDir;

    @Before
    public void setUp() throws Exception {
        pluginDir = appFolder.newFolder("target_dir").toPath();
        pluginDir.resolve("foo").toFile().createNewFile();
    }

    @Test
    public void test_create_link() throws Exception {
        PluginService.createLinkToPlugins(appFolder.getRoot().toPath(), PropertiesProvider.fromMap(new HashMap<String, String>() {{
            put(PLUGINS_DIR, pluginDir.toString());
        }}));
        assertThat(appFolder.getRoot().toPath().resolve("plugins").toFile()).exists();
        assertThat(appFolder.getRoot().toPath().resolve("plugins").toFile()).is(link);
    }

    @Test
    public void test_create_link_with_nonexistent_target() throws Exception {
        PluginService.createLinkToPlugins(appFolder.getRoot().toPath(), PropertiesProvider.fromMap(new HashMap<String, String>() {{
            put(PLUGINS_DIR, appFolder.getRoot().toPath().resolve("nonexistent").toString());
        }}));
        assertThat(appFolder.getRoot().toPath().resolve("plugins").toFile()).doesNotExist();
        assertThat(appFolder.getRoot().toPath().resolve("plugins").toFile()).isNot(link);
    }

    @Test
    public void test_create_link_with_existing_link() throws IOException {
        PluginService.createLinkToPlugins(appFolder.getRoot().toPath(), PropertiesProvider.fromMap(new HashMap<String, String>() {{
            put(PLUGINS_DIR, pluginDir.toString());
        }}));

        pluginDir = appFolder.newFolder("other_target_dir").toPath();
        PluginService.createLinkToPlugins(appFolder.getRoot().toPath(), PropertiesProvider.fromMap(new HashMap<String, String>() {{
            put(PLUGINS_DIR, pluginDir.toString());
        }}));

        assertThat(appFolder.getRoot().toPath().resolve("plugins").toFile()).exists();
        assertThat(appFolder.getRoot().toPath().resolve("plugins").toFile()).is(link);
        assertThat(appFolder.getRoot().toPath().resolve("plugins").toFile().listFiles()).isEmpty();
    }

    @Test
    public void test_create_link_with_existing_link_and_nonexistent_target_should_leave_previous_link() throws IOException {
        PluginService.createLinkToPlugins(appFolder.getRoot().toPath(), PropertiesProvider.fromMap(new HashMap<String, String>() {{
            put(PLUGINS_DIR, pluginDir.toString());
        }}));

        PluginService.createLinkToPlugins(appFolder.getRoot().toPath(), PropertiesProvider.fromMap(new HashMap<String, String>() {{
            put(PLUGINS_DIR, appFolder.getRoot().toPath().resolve("nonexistent").toString());
        }}));

        assertThat(appFolder.getRoot().toPath().resolve("plugins").toFile()).exists();
        assertThat(appFolder.getRoot().toPath().resolve("plugins").toFile()).is(link);
        assertThat(appFolder.getRoot().toPath().resolve("plugins").toFile().listFiles()).hasSize(1);
    }

    Condition<File> link = new Condition<File>() {
        @Override
        public boolean matches(File file) {
            return Files.isSymbolicLink(file.toPath());
        }
    };
}
