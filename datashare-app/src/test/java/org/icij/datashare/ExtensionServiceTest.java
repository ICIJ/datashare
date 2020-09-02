package org.icij.datashare;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.Extension.Type.WEB;
import static org.icij.datashare.PropertiesProvider.EXTENSIONS_DIR;

public class ExtensionServiceTest {
    @Rule public TemporaryFolder extensionFolder = new TemporaryFolder();
    @Rule public TemporaryFolder otherFolder = new TemporaryFolder();
    @Test
    public void test_get_list() {
        Set<Extension> extensions = new ExtensionService(extensionFolder.getRoot().toPath()).list();
        assertThat(extensions).hasSize(3);
        assertThat(extensions.stream().map(Extension::getId).collect(Collectors.toSet()))
                .containsOnly("my-extension-foo", "my-extension-baz", "my-extension-bar");
    }

    @Test
    public void test_get_extension() {
        Set<Extension> extensions = new ExtensionService(extensionFolder.getRoot().toPath()).list("my-extension-baz");
        assertThat(extensions).hasSize(1);
        assertThat(extensions.iterator().next().type).isEqualTo(WEB);
    }

    @Test
    public void test_extension_service_from_properties() {
        ExtensionService extensionService = new ExtensionService(new PropertiesProvider(new HashMap<String, String>() {{
            put(EXTENSIONS_DIR, extensionFolder.getRoot().getPath());
        }}));
        assertThat(extensionService.extensionsDir.toString()).isEqualTo(extensionFolder.getRoot().getPath());
    }

    @Test
    public void test_install_an_extension_from_id() throws IOException {
        otherFolder.newFile("my-extension.jar");
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                       "{\"id\":\"my-extension\", \"url\": \"" + otherFolder.getRoot().toPath().resolve("my-extension.jar").toUri() + "\"}" +
                       "]}").getBytes()));

        extensionService.downloadAndInstall("my-extension");

        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension.jar").toFile()).exists();
    }

    @Test
    public void test_download_and_install_extension_removes_temporary_file() throws Exception {
        otherFolder.newFile("extension.jar");

        Extension extension = new Extension(otherFolder.getRoot().toPath().resolve("extension.jar").toUri().toURL());
        File tmpFile = extension.download();
        extension.install(tmpFile, extensionFolder.getRoot().toPath());

        assertThat(tmpFile.getName()).startsWith("tmp");
        assertThat(tmpFile).doesNotExist();
        assertThat(extensionFolder.getRoot().toPath().resolve("extension.jar").toFile()).exists();
    }

    @Test
    public void test_install_an_extension_from_url() throws IOException {
        otherFolder.newFile("my-extension.jar");
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath());

        extensionService.downloadAndInstall(otherFolder.getRoot().toPath().resolve("my-extension.jar").toUri().toURL());

        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension.jar").toFile()).exists();
    }

    @Test
    public void test_install_an_extension_from_file() throws IOException {
        otherFolder.newFile("my-extension.jar");

        new Extension(otherFolder.getRoot().toPath().resolve("my-extension.jar").toUri().toURL()).install(extensionFolder.getRoot().toPath());

        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension.jar").toFile()).exists();
    }

    @Test
    public void test_delete_previous_extension_if_version_differs() throws Exception {
        otherFolder.newFile("my-extension-1.2.3.jar");
        otherFolder.newFile("my-extension-1.2.4.jar");
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath());

        extensionService.downloadAndInstall(otherFolder.getRoot().toPath().resolve("my-extension-1.2.3.jar").toUri().toURL());
        extensionService.downloadAndInstall(otherFolder.getRoot().toPath().resolve("my-extension-1.2.4.jar").toUri().toURL());

        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension-1.2.4.jar").toFile()).exists();
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension-1.2.3.jar").toFile()).doesNotExist();
    }

    @Test
    public void test_list_installed_extensions() throws Exception {
        extensionFolder.newFile("extension-1.jar");
        extensionFolder.newFile("extension-2.jar");

        assertThat(new ExtensionService(extensionFolder.getRoot().toPath()).listInstalled().stream().map(File::toString).collect(Collectors.toSet())).
                contains(extensionFolder.getRoot().toPath().resolve("extension-1.jar").toString(),
                        extensionFolder.getRoot().toPath().resolve("extension-2.jar").toString());
    }

    @Test
    public void test_list_installed_extensions_with_different_id_and_filename() throws Exception {
        extensionFolder.newFile( "official-extension-version-jar-with-dependencies.jar");
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                "{\"id\":\"official-extension\", \"url\": \"" + otherFolder.getRoot().toPath().resolve("official-extension-version-jar-with-dependencies.jar").toUri() + "\"}" +
                "]}").getBytes()));
        Set<Extension> list = extensionService.list();
        assertThat(list.iterator().next().isInstalled(extensionFolder.getRoot().toPath())).isTrue();
    }

    @Test
    public void test_list_installed_extensions_empty() {
        assertThat(new ExtensionService(extensionFolder.getRoot().toPath()).listInstalled().stream().map(File::toString).collect(Collectors.toSet())).isEmpty();
    }

    @Test
    public void test_list_merges_with_installed() throws IOException {
        extensionFolder.newFile( "official-extension.jar");
        extensionFolder.newFile( "custom-extension.jar");
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                "{\"id\":\"official-extension\", \"url\": \"" + otherFolder.getRoot().toPath().resolve("official-extension.jar").toUri() + "\"}" +
                "]}").getBytes()));

        Set<Extension> list = extensionService.list();
        Iterator<Extension> extensionsIterator = list.iterator();
        assertThat(extensionsIterator.next().isInstalled(extensionFolder.getRoot().toPath())).isTrue();
        assertThat(extensionsIterator.next().isInstalled(extensionFolder.getRoot().toPath())).isTrue();
    }

    @Test
    public void test_list_merges_with_identical_extension_from_json_and_from_folder() throws IOException {
        extensionFolder.newFile( "official-extension-7.0.0-with-dependencies.jar");
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                "{\"id\":\"official-extension\", \"url\": \"" + otherFolder.getRoot().toPath().resolve("official-extension-7.0.0-with-dependencies.jar").toUri() + "\"}" +
                "]}").getBytes()));
        Set<Extension> list = extensionService.list();
        assertThat(list).hasSize(1);
    }

    @Test
    public void test_delete_extension_by_id() throws IOException {
        extensionFolder.newFile( "my-extension.jar");
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                "{\"id\":\"my-extension\", \"url\": \"" + otherFolder.getRoot().toPath().resolve("my-extension.jar").toUri() + "\"}" +
                "]}").getBytes()));

        extensionService.delete("my-extension");
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension.jar").toFile()).doesNotExist();

    }

    @Test
    public void test_delete_extension_by_url() throws IOException {
        extensionFolder.newFile( "my-extension.jar");
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath());
        Properties properties = PropertiesProvider.fromMap(new HashMap<String, String>() {{
            put("extensionDelete", extensionFolder.getRoot().toPath().resolve("my-extension.jar").toString());
        }});

        extensionService.deleteFromCli(properties);
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension.jar").toFile()).doesNotExist();
    }
}
