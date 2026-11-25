package org.icij.datashare;


import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toSet;
import static org.apache.commons.io.FilenameUtils.getExtension;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.ExtensionServiceTest.WRONG_SHA256;
import static org.icij.datashare.ExtensionServiceTest.aRegistryEntry;
import static org.icij.datashare.ExtensionServiceTest.aRegistryOf;
import static org.icij.datashare.ExtensionServiceTest.withTemporaryDirectory;
import static org.junit.Assert.assertThrows;

public class PluginServiceTest {
    @Rule public TemporaryFolder pluginFolder = new TemporaryFolder();
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();
    @Rule public TemporaryFolder extensionFolder = new TemporaryFolder();
    @Rule public TemporaryFolder otherFolder = new TemporaryFolder();

    @Test
    public void test_get_plugin_url() throws Exception {
        pluginFolder.newFolder("target_dir", "my_plugin").toPath().resolve("index.js").toFile().createNewFile();
        assertThat(new PluginService().getPluginUrl(pluginFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin"))).
                isEqualTo("/plugins/my_plugin/index.js");
    }

    @Test
    public void test_get_plugin_url_with_subdirectory() throws Exception {
        pluginFolder.newFolder("target_dir", "my_plugin", "dist").toPath().resolve("main.js").toFile().createNewFile();
        Path packageJson = pluginFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin").resolve("package.json");
        Files.write(packageJson, asList("{", "\"main\":\"dist/main.js\"", "}"));

        assertThat(new PluginService().getPluginUrl(pluginFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin"))).
                isEqualTo("/plugins/my_plugin/dist/main.js");
    }

    @Test
    public void test_project_filter_json_without_private() throws IOException {
        pluginFolder.newFolder("target_dir", "my_plugin").toPath().resolve("package.json").toFile().createNewFile();
        Path packageJson = pluginFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin");
        Files.write(packageJson.resolve("package.json"), asList("{", "\"main\":\"dist/main.js\"", "}"));
        assertThat(new PluginService().projectFilter(packageJson, asList("Toto", "Tata")).toString()).isEqualTo(packageJson.toString());
    }

    @Test
    public void test_project_filter_json_with_private_false() throws IOException {
        pluginFolder.newFolder("target_dir", "my_plugin").toPath().resolve("package.json").toFile().createNewFile();
        Path packageJson = pluginFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin");
        Files.write(packageJson.resolve("package.json"),asList(
                "{",
                "  \"private\": false",
                "}"
        ));
        assertThat(new PluginService().projectFilter(packageJson,asList("Toto","Tata")).toString()).isEqualTo(packageJson.toString());
    }

    @Test
    public void test_project_filter_json_with_private_true_without_projects() throws IOException {
        pluginFolder.newFolder("target_dir", "my_plugin").toPath().resolve("package.json").toFile().createNewFile();
        Path packageJson = pluginFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin");
        Files.write(packageJson.resolve("package.json"),asList(
                "{",
                "  \"private\": true",
                "}"
        ));
        assertThat(new PluginService().projectFilter(packageJson,asList("Toto","Tata"))).isNull();
    }

    @Test
    public void test_project_filter_json_with_private_true_with_projects_ok() throws IOException {
        pluginFolder.newFolder("target_dir", "my_plugin").toPath().resolve("package.json").toFile().createNewFile();
        Path packageJson = pluginFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin");
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
        pluginFolder.newFolder("target_dir", "my_plugin").toPath().resolve("package.json").toFile().createNewFile();
        Path packageJson = pluginFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin");
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
    public void test_list_plugins_from_plugins_json_file() {
        Set<DeliverablePackage> plugins = new PluginService().list();

        assertThat(plugins).hasSize(3);
        assertThat(plugins.stream().map(DeliverablePackage::getId).collect(toSet()))
                .containsOnly("my-plugin-foo", "my-plugin-baz", "my-plugin-bar");
    }

    @Test
    public void test_list_installed_plugins() throws IOException {
        pluginFolder.newFolder("my-plugin");
        pluginFolder.newFolder("datashare-plugin-site-toto-1.3.3");
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                "{\"id\":\"my-plugin\", \"url\": \"" + ClassLoader.getSystemResource("my-plugin.tgz") + "\"}" +
                "]}").getBytes()));
        Set<DeliverablePackage> list = pluginService.list();
        Iterator<DeliverablePackage> pluginIterator = list.iterator();
        assertThat(list).hasSize(2);
        assertThat(pluginIterator.next().isInstalled()).isTrue();
        assertThat(pluginIterator.next().isInstalled()).isTrue();
    }
    @Test
    public void test_list_installed_plugins_with_v_prefix() throws IOException {
        pluginFolder.newFolder("my-plugin-v2.0.0");
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                "{\"id\":\"my-plugin\", \"version\": \"2.0.0\", \"url\": \"" + ClassLoader.getSystemResource("my-plugin-v2.0.0.tgz") + "\"}" +
                "]}").getBytes()));
        Set<DeliverablePackage> list = pluginService.list();
        Iterator<DeliverablePackage> pluginIterator = list.iterator();
        assertThat(list).hasSize(1);
        assertThat(pluginIterator.next().isInstalled()).isTrue();
    }

    @Test
    public void test_plugin_properties() throws Exception {
        DeliverablePackage plugin = new PluginService().list("my-plugin-foo").iterator().next();
        assertThat(plugin.getId()).isEqualTo("my-plugin-foo");
        assertThat(plugin.getDescription()).isEqualTo("description for foo");
        assertThat(plugin.getName()).isEqualTo("Foo Plugin");
        assertThat(plugin.getVersion()).isEqualTo("1.2.3");
    }

    @Test
    public void test_list_plugins_from_plugins_json_file_with_pattern() throws Exception {
        PluginService pluginService = new PluginService();
        assertThat(pluginService.list(".*")).hasSize(3);
        assertThat(pluginService.list(".*foo.*")).hasSize(1);
        assertThat(pluginService.list(".*baz.*")).hasSize(1);
        assertThat(pluginService.list(".*baz.*").iterator().next().getId()).isEqualTo("my-plugin-baz");
        assertThat(pluginService.list(".*ba.*")).hasSize(2);
    }

    @Test(expected = DeliverableRegistry.UnknownDeliverableException.class)
    public void test_download_unknown_plugin() throws Exception {
        new PluginService(pluginFolder.getRoot().toPath()).downloadAndInstall("unknown-plugin");
    }

    @Test
    public void test_download_and_install_tgz_plugin() throws Exception {
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                "{\"id\":\"my-plugin\", \"url\": \"" + ClassLoader.getSystemResource("my-plugin.tgz") + "\"}" +
                "]}").getBytes()));

        File tmpFile = pluginService.deliverableRegistry.get("my-plugin").download();
        assertThat(getExtension(tmpFile.getPath())).isEqualTo("tgz");
        assertThat(tmpFile.getName()).startsWith("tmp");
        tmpFile.delete();

        withTemporaryDirectory(temporaryFolder.getRoot(), () -> pluginService.downloadAndInstall("my-plugin"));

        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").toFile()).exists();
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").resolve("package.json").toFile()).exists();
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").resolve("main.js").toFile()).exists();
        assertThat(temporaryFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_download_and_install_zip_plugin() throws Exception {
        new PluginService(pluginFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                "{\"id\":\"my-plugin\", \"url\": \"" + ClassLoader.getSystemResource("my-plugin.zip")+ "\"}" +
                "]}").getBytes())).downloadAndInstall("my-plugin");
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").toFile()).exists();
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").resolve("package.json").toFile()).exists();
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").resolve("main.js").toFile()).exists();
    }

    @Test
    public void download_and_install_from_url() throws Exception {
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath());
        pluginService.downloadAndInstall(ClassLoader.getSystemResource("my-plugin.tgz"));

        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").toFile()).exists();
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").resolve("package.json").toFile()).exists();
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").resolve("main.js").toFile()).exists();
    }

    @Test(expected = FileNotFoundException.class)
    public void test_install_bad_filename() throws Exception {
        new Plugin(new File("not a file name").toURI().toURL()).install(pluginFolder.getRoot().toPath());
    }

    @Test
    public void test_install_from_file() throws Exception {
        File pluginFile = new File(ClassLoader.getSystemResource("my-plugin.tgz").getPath());
        new Plugin(pluginFile.toURI().toURL()).install(pluginFolder.getRoot().toPath());

        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").toFile()).exists();
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").resolve("package.json").toFile()).exists();
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").resolve("main.js").toFile()).exists();
        assertThat(pluginFile).exists();
    }

    @Test
    public void test_delete_plugin_by_id() throws Exception {
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                        "{\"id\":\"my-plugin\", \"url\": \"" + ClassLoader.getSystemResource("my-plugin.tgz") + "\"}" +
                        "]}").getBytes()));
        pluginService.downloadAndInstall(ClassLoader.getSystemResource("my-plugin.tgz"));
        Files.createDirectory(pluginFolder.getRoot().toPath().resolve("my-custom-plugin"));

        pluginService.delete("my-plugin");
        pluginService.delete("my-custom-plugin");
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").toFile()).doesNotExist();
        assertThat(pluginFolder.getRoot().toPath().resolve("my-custom-plugin").toFile()).doesNotExist();
    }

    @Test
    public void test_delete_plugin_by_id_with_v_preffix() throws Exception {
        pluginFolder.newFolder("my-plugin-v2.0.0");
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                "{\"id\":\"my-plugin\", \"version\": \"2.0.0\", \"url\": \"" + ClassLoader.getSystemResource("my-plugin-v2.0.0.tgz") + "\"}" +
                "]}").getBytes()));
        pluginService.downloadAndInstall(ClassLoader.getSystemResource("my-plugin-v2.0.0.tgz"));
        pluginService.delete("my-plugin");
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin-v2.0.0").toFile()).doesNotExist();
    }

    @Test
    public void test_delete_plugin_by_base_directory() throws Exception {
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath());
        URL pluginUrl = ClassLoader.getSystemResource("my-plugin.tgz");
        pluginService.downloadAndInstall(pluginUrl);
        Properties properties = PropertiesProvider.fromMap(new HashMap<>() {{
            put("pluginDelete", "my-plugin");
        }});

        pluginService.deleteFromCli(properties);
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").toFile()).doesNotExist();
    }

    @Test
    public void test_delete_previous_extension_if_version_differs_with_id() throws Exception {
        // Create a file for an older version (0.5.0)
        pluginFolder.newFile("my-plugin-0.5.0.tar.gz");
        // Create a folder for the current plugin version (1.0.0)
        pluginFolder.newFolder("my-plugin-1.0.0");

        // Prepare the PluginService with a new plugin version
        String version = "1.1.0";
        String pluginUrl = ClassLoader.getSystemResource("my-plugin-" + version + ".tgz").toString();
        String pluginMetadata = String.format("{\"deliverableList\": [{\"id\":\"my-plugin\", \"version\": \"%s\", \"url\": \"%s\"}]}", version, pluginUrl);
        ByteArrayInputStream inputStream = new ByteArrayInputStream(pluginMetadata.getBytes());
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath(), inputStream);

        // Install the new plugin
        pluginService.downloadAndInstall("my-plugin");

        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin-1.0.0").toFile()).doesNotExist();
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin-1.1.0").toFile()).exists();
    }

    @Test
    public void test_delete_previous_extension_if_version_use_v_prefix() throws Exception {
        // Create a folder for the current plugin version (1.0.0)
        pluginFolder.newFolder("my-plugin-1.0.0");

        // Prepare the PluginService with a new plugin version
        String version = "2.0.0";
        String pluginUrl = ClassLoader.getSystemResource("my-plugin-v" + version + ".tgz").toString();
        String pluginMetadata = String.format("{\"deliverableList\": [{\"id\":\"my-plugin\", \"version\": \"%s\", \"url\": \"%s\"}]}", version, pluginUrl);
        ByteArrayInputStream inputStream = new ByteArrayInputStream(pluginMetadata.getBytes());
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath(), inputStream);

        // Install the new plugin
        pluginService.downloadAndInstall("my-plugin");

        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin-1.0.0").toFile()).doesNotExist();
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin-v2.0.0").toFile()).exists();
    }

    @Test
    public void test_install_plugin_with_extension() throws IOException {
        // Given
        File extensionDir = pluginFolder.newFolder("my-extension-dir");
        String extensionRegistry = ""
            + "{"
            + "    \"deliverableList\": ["
            + "        {"
            + "            \"id\":\"my-extension\","
            + "            \"url\": \"" + ClassLoader.getSystemResource("my-extension-1.0.1.jar") + "\""
            + "        }"
            + "     ]"
            + "}";
        ExtensionService extensionService = new ExtensionService(extensionDir.toPath(), new ByteArrayInputStream(extensionRegistry.getBytes()));
        String pluginRegistry = ""
            + "{"
            + "    \"deliverableList\": ["
            + "        {"
            + "            \"id\":\"my-plugin-with-extension\", "
            + "            \"url\": \"" + ClassLoader.getSystemResource("my-plugin-1.1.0.tgz") + "\","
            + "            \"extensions\": [\"my-extension\"]"
            + "        }"
            + "     ]"
            + "}";
        PluginService pluginService = new PluginService(
            pluginFolder.getRoot().toPath(),
            new ByteArrayInputStream(pluginRegistry.getBytes()), extensionService
        );
        // When
        pluginService.downloadAndInstall("my-plugin-with-extension");
        // Then
        assertThat(extensionDir.toPath().resolve("my-extension-1.0.1.jar").toFile()).exists();
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin-1.1.0").toFile()).exists();
    }

    @Test
    public void test_install_plugin_with_wrong_sha256_does_not_install_its_extensions() {
        PluginService pluginService = aPluginServiceWith(
                aRegistryEntry("my-extension", ClassLoader.getSystemResource("my-extension-1.0.1.jar")),
                aPluginEntryWithExtension(ClassLoader.getSystemResource("my-plugin-1.1.0.tgz"), WRONG_SHA256));

        assertThrows(Extension.InvalidDeliverableException.class,
                () -> pluginService.downloadAndInstall("my-plugin-with-extension"));

        assertThat(extensionFolder.getRoot().list()).isEmpty();
        assertThat(pluginFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_plugin_with_refused_archive_does_not_install_its_extensions() throws Exception {
        PluginService pluginService = aPluginServiceWith(
                aRegistryEntry("my-extension", ClassLoader.getSystemResource("my-extension-1.0.1.jar")),
                aPluginEntryWithExtension(aZipWithEntries("malicious.zip", "../../evil.txt")));

        assertThrows(Extension.InvalidDeliverableException.class,
                () -> pluginService.downloadAndInstall("my-plugin-with-extension"));

        assertThat(extensionFolder.getRoot().list()).isEmpty();
        assertThat(pluginFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_plugin_whose_extension_is_refused_leaves_nothing_behind() throws Exception {
        PluginService pluginService = aPluginServiceWith(
                aRegistryEntry("my-extension", ClassLoader.getSystemResource("my-extension-1.0.1.jar"), WRONG_SHA256),
                aPluginEntryWithExtension(ClassLoader.getSystemResource("my-plugin-1.1.0.tgz")));

        withTemporaryDirectory(temporaryFolder.getRoot(), () -> assertThrows(
                Extension.InvalidDeliverableException.class,
                () -> pluginService.downloadAndInstall("my-plugin-with-extension")));

        assertThat(extensionFolder.getRoot().list()).isEmpty();
        assertThat(pluginFolder.getRoot().list()).isEmpty();
        assertThat(temporaryFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_upgrade_with_wrong_sha256_keeps_the_installed_version() throws IOException {
        pluginFolder.newFolder("my-plugin-1.0.0");
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath(), aRegistryOf(
                aRegistryEntry("my-plugin", ClassLoader.getSystemResource("my-plugin-1.1.0.tgz"), WRONG_SHA256)));

        assertThrows(Extension.InvalidDeliverableException.class, () -> pluginService.downloadAndInstall("my-plugin"));

        assertThat(pluginFolder.getRoot().list()).containsOnly("my-plugin-1.0.0");
    }

    @Test
    public void test_upgrade_with_refused_archive_keeps_the_installed_version() throws Exception {
        pluginFolder.newFolder("my-plugin-1.0.0");
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath(), aRegistryOf(
                aRegistryEntry("my-plugin", aZipWithEntries("my-plugin-1.1.0.zip", "../../evil.txt"))));

        assertThrows(Extension.InvalidDeliverableException.class, () -> pluginService.downloadAndInstall("my-plugin"));

        assertThat(pluginFolder.getRoot().list()).containsOnly("my-plugin-1.0.0");
    }

    @Test
    public void test_upgrade_removes_the_previous_version() throws IOException {
        pluginFolder.newFolder("my-plugin-1.0.0");
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath(), aRegistryOf(
                aRegistryEntry("my-plugin", ClassLoader.getSystemResource("my-plugin-1.1.0.tgz"))));

        pluginService.downloadAndInstall("my-plugin");

        assertThat(pluginFolder.getRoot().list()).containsOnly("my-plugin-1.1.0");
    }

    @Test
    public void test_install_archive_without_directory_entries() throws Exception {
        URL archive = aZipWithEntries("my-plugin-1.0.0.zip", "my-plugin-1.0.0/index.js");

        new Plugin(archive).install(new File(archive.toURI()), pluginFolder.getRoot().toPath());

        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin-1.0.0").resolve("index.js").toFile()).exists();
    }

    @Test
    public void test_install_from_cli_with_registry_url_checks_registry_entry_sha256() {
        URL registryUrl = ClassLoader.getSystemResource("my-plugin-1.1.0.tgz");
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath(), aRegistryOf(
                aRegistryEntry("my-plugin", registryUrl, WRONG_SHA256)));

        assertThrows(Extension.InvalidDeliverableException.class,
                () -> pluginService.downloadAndInstallFromCli(cliProperties("pluginInstall", registryUrl.toString())));

        assertThat(pluginFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_from_unregistered_url_when_allowed() throws Exception {
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath()) {
            @Override
            boolean isAllowedUnregistered(URL unregistered) {
                return true; // stands for the option being on, without needing an https server
            }
        };

        pluginService.downloadAndInstallFromRemote(ClassLoader.getSystemResource("my-plugin.tgz"));

        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").resolve("package.json").toFile()).exists();
    }

    @Test
    public void test_install_from_cli_with_registry_url_installs_the_declared_extension() throws IOException {
        URL registryUrl = ClassLoader.getSystemResource("my-plugin-1.1.0.tgz");
        PluginService pluginService = aPluginServiceWith(
                aRegistryEntry("my-extension", ClassLoader.getSystemResource("my-extension-1.0.1.jar")),
                aPluginEntryWithExtension(registryUrl));

        pluginService.downloadAndInstallFromCli(cliProperties("pluginInstall", registryUrl.toString()));

        assertThat(extensionFolder.getRoot().list()).containsOnly("my-extension-1.0.1.jar");
        assertThat(pluginFolder.getRoot().list()).containsOnly("my-plugin-1.1.0");
    }

    @Test
    public void test_install_from_cli_with_unregistered_url() throws IOException {
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath());

        pluginService.downloadAndInstallFromCli(
                cliProperties("pluginInstall", ClassLoader.getSystemResource("my-plugin.tgz").toString()));

        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").resolve("package.json").toFile()).exists();
    }

    @Test
    public void test_install_from_cli_with_local_file_keeps_the_source_file() throws Exception {
        File source = otherFolder.newFile("tmp-my-plugin.tgz"); // "tmp" prefix: such a file used to be deleted
        Files.copy(Paths.get(ClassLoader.getSystemResource("my-plugin.tgz").toURI()), source.toPath(), REPLACE_EXISTING);
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath());

        pluginService.downloadAndInstallFromCli(cliProperties("pluginInstall", source.getPath()));

        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").resolve("package.json").toFile()).exists();
        assertThat(source).exists();
    }

    @Test
    public void test_upgrade_keeps_the_installed_version_when_the_copy_fails() throws IOException {
        pluginFolder.newFolder("my-plugin-1.0.0");
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath(), aRegistryOf(
                aRegistryEntry("my-plugin", ClassLoader.getSystemResource("my-plugin-1.1.0.tgz"))));
        assertThat(pluginFolder.getRoot().setWritable(false)).isTrue();
        try {
            IOException copyFailed = assertThrows(IOException.class, () -> pluginService.downloadAndInstall("my-plugin"));
            assertThat(copyFailed instanceof Extension.InvalidDeliverableException).isFalse();
        } finally {
            pluginFolder.getRoot().setWritable(true);
        }

        assertThat(pluginFolder.getRoot().list()).containsOnly("my-plugin-1.0.0");
    }

    @Test
    public void test_reinstall_of_the_same_version_replaces_the_directory() throws IOException {
        pluginFolder.newFolder("my-plugin-1.1.0");
        pluginFolder.newFile("my-plugin-1.1.0/not-in-the-archive.js");
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath(), aRegistryOf(
                aRegistryEntry("my-plugin", ClassLoader.getSystemResource("my-plugin-1.1.0.tgz"))));

        pluginService.downloadAndInstall("my-plugin");

        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin-1.1.0").toFile().list())
                .containsOnly("main.js", "package.json");
    }

    @Test
    public void test_install_keeps_a_plugin_whose_name_only_shares_the_prefix() throws IOException {
        pluginFolder.newFolder("my-plugin-extra-2.0.0");
        pluginFolder.newFolder("my-plugin-1.0.0");
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath(), aRegistryOf(
                aRegistryEntry("my-plugin", ClassLoader.getSystemResource("my-plugin-1.1.0.tgz"))));

        pluginService.downloadAndInstall("my-plugin");

        assertThat(pluginFolder.getRoot().list()).containsOnly("my-plugin-extra-2.0.0", "my-plugin-1.1.0");
    }

    @Test
    public void test_install_rejects_zip_slip_entry_without_repeating_its_name() throws Exception {
        URL maliciousZip = aZipWithEntries("malicious.zip", "../../evil.txt");

        Extension.InvalidDeliverableException refused = assertThrows(Extension.InvalidDeliverableException.class,
                () -> new Plugin(maliciousZip).install(new File(maliciousZip.toURI()), pluginFolder.getRoot().toPath()));

        assertThat(refused.getMessage()).contains("escapes target directory").excludes("evil");
        assertThat(pluginFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_rejects_conflicting_entries_without_a_server_path() throws Exception {
        URL conflictingZip = aZipWithEntries("conflict.zip", "a", "a/");

        Extension.InvalidDeliverableException refused = assertThrows(Extension.InvalidDeliverableException.class,
                () -> new Plugin(conflictingZip).install(new File(conflictingZip.toURI()), pluginFolder.getRoot().toPath()));

        assertThat(refused.getMessage()).isEqualTo("Archive entries conflict with each other");
        assertThat(refused.getCause()).isNull(); // the cause would carry the temp path and the entry name into the log
        assertThat(pluginFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_upgrade_with_empty_archive_keeps_the_installed_version() throws Exception {
        pluginFolder.newFolder("my-plugin-1.0.0");
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath(), aRegistryOf(
                aRegistryEntry("my-plugin", aZipWithEntries("my-plugin-1.1.0.zip"))));

        Extension.InvalidDeliverableException refused = assertThrows(Extension.InvalidDeliverableException.class,
                () -> pluginService.downloadAndInstall("my-plugin"));

        assertThat(refused.getMessage()).isEqualTo("Archive has no entry");
        assertThat(pluginFolder.getRoot().list()).containsOnly("my-plugin-1.0.0");
    }

    @Test
    public void test_install_rejects_truncated_archive() throws Exception {
        byte[] archive = Files.readAllBytes(Paths.get(ClassLoader.getSystemResource("my-plugin.tgz").toURI()));
        File truncated = otherFolder.newFile("truncated-1.0.0.tgz");
        Files.write(truncated.toPath(), Arrays.copyOf(archive, archive.length / 2));

        Extension.InvalidDeliverableException refused = assertThrows(Extension.InvalidDeliverableException.class,
                () -> new Plugin(truncated.toURI().toURL()).install(truncated, pluginFolder.getRoot().toPath()));

        assertThat(refused.getMessage()).contains("not a readable plugin archive");
        assertThat(pluginFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_rejects_too_many_entries() throws Exception {
        File bomb = otherFolder.newFile("bomb.zip");
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(bomb)) {
            for (int i = 0; i <= 10_001; i++) {
                zos.putArchiveEntry(new ZipArchiveEntry("file" + i + ".txt"));
                zos.closeArchiveEntry();
            }
        }

        Extension.InvalidDeliverableException refused = assertThrows(Extension.InvalidDeliverableException.class,
                () -> new Plugin(bomb.toURI().toURL()).install(bomb, pluginFolder.getRoot().toPath()));

        assertThat(refused.getMessage()).contains("too many entries");
        assertThat(pluginFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_rejects_oversized_entry() throws Exception {
        File bomb = otherFolder.newFile("bomb.tar");
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(new FileOutputStream(bomb))) {
            long size = 101L * 1024 * 1024; // just over 100 MB
            TarArchiveEntry entry = new TarArchiveEntry("huge.bin");
            entry.setSize(size);
            tos.putArchiveEntry(entry);
            byte[] chunk = new byte[8192];
            long written = 0;
            while (written < size) {
                int len = (int) Math.min(chunk.length, size - written);
                tos.write(chunk, 0, len);
                written += len;
            }
            tos.closeArchiveEntry();
        }

        Extension.InvalidDeliverableException refused = assertThrows(Extension.InvalidDeliverableException.class,
                () -> new Plugin(bomb.toURI().toURL()).install(bomb, pluginFolder.getRoot().toPath()));

        assertThat(refused.getMessage()).contains("exceeds size limit");
        assertThat(pluginFolder.getRoot().list()).isEmpty();
    }

    private PluginService aPluginServiceWith(String extensionEntry, String pluginEntry) {
        ExtensionService extensionService =
                new ExtensionService(extensionFolder.getRoot().toPath(), aRegistryOf(extensionEntry));
        return new PluginService(pluginFolder.getRoot().toPath(), aRegistryOf(pluginEntry), extensionService);
    }

    private static String aPluginEntryWithExtension(URL url) {
        return aPluginEntryWithExtension(url, null);
    }

    private static String aPluginEntryWithExtension(URL url, String sha256) {
        return "{\"id\":\"my-plugin-with-extension\", \"url\": \"" + url + "\", \"extensions\": [\"my-extension\"]" +
               (sha256 == null ? "" : ", \"sha256\": \"" + sha256 + "\"") + "}";
    }

    private static Properties cliProperties(String option, String value) {
        return PropertiesProvider.fromMap(Map.of(option, value));
    }

    /** A zip, outside the plugins directory. A name ending with "/" is a directory entry, any other a file. */
    private URL aZipWithEntries(String zipName, String... entryNames) throws IOException {
        File zip = otherFolder.newFile(zipName);
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(zip)) {
            for (String entryName : entryNames) {
                zos.putArchiveEntry(new ZipArchiveEntry(entryName));
                if (!entryName.endsWith("/")) {
                    zos.write("content".getBytes());
                }
                zos.closeArchiveEntry();
            }
        }
        return zip.toURI().toURL();
    }
}
