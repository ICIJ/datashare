package org.icij.datashare;

import java.io.InputStream;
import java.nio.file.Path;
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
import static org.icij.datashare.Deliverable.Type.WEB;
import static org.icij.datashare.PropertiesProvider.EXTENSIONS_DIR;

public class ExtensionServiceTest {
    @Rule public TemporaryFolder extensionFolder = new TemporaryFolder();
    @Rule public TemporaryFolder otherFolder = new TemporaryFolder();

    public static ExtensionService createExtensionService(Path extensionsDir,
                                                          InputStream inputStream) {
        return new ExtensionService(extensionsDir, inputStream);
    }

    @Test
    public void test_get_list() {
        Set<DeliverablePackage> extensions = new ExtensionService(extensionFolder.getRoot().toPath()).list();
        assertThat(extensions).hasSize(3);
        assertThat(extensions.stream().map(d -> d.reference().getId()).collect(Collectors.toSet()))
                .containsOnly("my-extension-foo", "my-extension-baz", "my-extension-bar");
    }

    @Test
    public void test_get_extension() {
        Set<DeliverablePackage> extensions = new ExtensionService(extensionFolder.getRoot().toPath()).list("my-extension-baz");
        assertThat(extensions).hasSize(1);
        assertThat(extensions.iterator().next().reference().getType()).isEqualTo(WEB);
    }

    @Test
    public void test_get_extension_with_id_pattern() {
        Set<DeliverablePackage> extensions = new ExtensionService(extensionFolder.getRoot().toPath()).list("my-extension-ba");
        assertThat(extensions).hasSize(2);
    }

    @Test
    public void test_get_extension_with_description_pattern() {
        Set<DeliverablePackage> extensions = new ExtensionService(extensionFolder.getRoot().toPath()).list("description for ba");
        assertThat(extensions).hasSize(2);
    }

    @Test
    public void test_extension_service_from_properties() {
        ExtensionService extensionService = new ExtensionService(new PropertiesProvider(new HashMap<>() {{
            put(EXTENSIONS_DIR, extensionFolder.getRoot().getPath());
        }}));
        assertThat(extensionService.deliverablesDir.toString()).isEqualTo(extensionFolder.getRoot().getPath());
    }

    @Test
    public void test_install_an_extension_from_id() throws IOException {
        otherFolder.newFile("my-extension-1.0.1.jar");
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                       "{\"id\":\"my-extension\", \"url\": \"" + otherFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toUri() + "\"}" +
                       "]}").getBytes()));

        extensionService.downloadAndInstall("my-extension");

        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toFile()).exists();
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toFile().canExecute()).isTrue();
    }

    @Test
    public void test_download_and_install_extension_removes_temporary_file() throws Exception {
        otherFolder.newFile("my-extension-1.0.1.jar");

        Extension extension = new Extension(otherFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toUri().toURL());
        File tmpFile = extension.download();
        extension.install(tmpFile, extensionFolder.getRoot().toPath());

        assertThat(tmpFile.getName()).startsWith("tmp");
        assertThat(tmpFile).doesNotExist();
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toFile()).exists();
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toFile().canExecute()).isTrue();
    }

    @Test
    public void test_install_an_extension_from_url() throws IOException {
        otherFolder.newFile("my-extension-1.0.1.jar");
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath());

        extensionService.downloadAndInstall(otherFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toUri().toURL());

        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toFile()).exists();
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toFile().canExecute()).isTrue();
    }

    @Test
    public void test_install_an_extension_from_file() throws IOException {
        otherFolder.newFile("my-extension-1.0.1.jar");

        new Extension(otherFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toUri().toURL()).install(extensionFolder.getRoot().toPath());

        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toFile()).exists();
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toFile().canExecute()).isTrue();
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
    public void test_delete_previous_extension_if_version_differs_for_executable_ext() throws Exception {
        OsArchDetector detector = new OsArchDetector();
        String v0Path = "my-extension-" + detector.osArchSuffix() + "-1.2.3";
        String v1path = "my-extension-" + detector.osArchSuffix() + "-1.2.4";
        otherFolder.newFile(v0Path);
        otherFolder.newFile(v1path);
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath());

        extensionService.downloadAndInstall(otherFolder.getRoot().toPath().resolve("my-extension-1.2.3").toUri().toURL());
        extensionService.downloadAndInstall(otherFolder.getRoot().toPath().resolve("my-extension-1.2.4").toUri().toURL());

        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension-1.2.3").toFile()).doesNotExist();
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension-1.2.4").toFile()).exists();
    }

    @Test
    public void test_list_installed_extensions() throws Exception {
        extensionFolder.newFile("extension-1.jar");
        extensionFolder.newFile("extension-2.jar");
        extensionFolder.newFile("extension-3");

        assertThat(new ExtensionService(extensionFolder.getRoot().toPath()).listInstalled().stream().map(File::toString).collect(Collectors.toSet())).
                contains(extensionFolder.getRoot().toPath().resolve("extension-1.jar").toString(),
                        extensionFolder.getRoot().toPath().resolve("extension-2.jar").toString(),
                    extensionFolder.getRoot().toPath().resolve("extension-3").toString()
                    );
    }

    @Test
    public void test_list_installed_extensions_empty() {
        assertThat(new ExtensionService(extensionFolder.getRoot().toPath()).listInstalled().stream().map(File::toString).collect(Collectors.toSet())).isEmpty();
    }

    @Test
    public void test_list_merges_with_installed() throws IOException {
        extensionFolder.newFile( "official-extension-1.0.1.jar");
        extensionFolder.newFile( "custom-my-extension-1.0.1.jar");
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                "{\"id\":\"official-extension\",\"type\":\"NLP\",\"url\": \"" + otherFolder.getRoot().toPath().resolve("official-extension-1.0.1.jar").toUri() + "\"}" +
                "]}").getBytes()));
        Set<DeliverablePackage> list = extensionService.list();
        Iterator<DeliverablePackage> extensionsIterator = list.iterator();
        assertThat(extensionsIterator.next().isInstalled()).isTrue();
        assertThat(extensionsIterator.next().isInstalled()).isTrue();
    }

    @Test
    public void test_list_merges_with_possible_upgrade() throws IOException {
        extensionFolder.newFile( "official-extension-7.0.0.jar");
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                "{\"id\":\"official-extension\",\"version\":\"7.1.0\",\"type\":\"NLP\",\"url\": \"" + otherFolder.getRoot().toPath().resolve("official-extension-7.1.0-with-dependencies.jar").toUri() + "\"}" +
                "]}").getBytes()));
        Set<DeliverablePackage> list = extensionService.list();
        assertThat(list).hasSize(1);
    }

    @Test
    public void test_list_merges_with_two_different_version_installed() throws IOException {
        extensionFolder.newFile( "official-extension-7.0.0.jar");
        extensionFolder.newFile( "official-extension-7.0.1.jar");
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                "{\"id\":\"official-extension\",\"version\":\"7.1.0\",\"type\":\"NLP\",\"url\": \"" + otherFolder.getRoot().toPath().resolve("official-extension-7.1.0-with-dependencies.jar").toUri() + "\"}" +
                "]}").getBytes()));
        Set<DeliverablePackage> list = extensionService.list();
        assertThat(list).hasSize(1);
        assertThat(list.iterator().next().getVersion()).isEqualTo("7.0.1");
    }

    @Test
    public void test_list_merge_with_suffix() throws IOException {
        extensionFolder.newFile( "official-extension-7.1.0-with-dependencies.jar");
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                "{\"id\":\"official-extension\",\"version\":\"7.1.0\",\"type\":\"NLP\",\"url\": \"" + otherFolder.getRoot().toPath().resolve("official-extension-7.1.0-with-dependencies.jar").toUri() + "\"}" +
                "]}").getBytes()));
        Set<DeliverablePackage> list = extensionService.list();
        Iterator<DeliverablePackage> extensionsIterator = list.iterator();
        assertThat(list).hasSize(1);
        assertThat(extensionsIterator.next().getId()).isEqualTo("official-extension");
    }

    @Test
    public void test_delete_extension_by_id() throws IOException {
        extensionFolder.newFile("my-extension-1.0.1.jar");
        extensionFolder.newFile("my-custom-extension-1.0.0.jar");
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                "{\"id\":\"my-extension\",\"type\":\"NLP\",\"url\": \"" + otherFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toUri() + "\"}" +
                "]}").getBytes()));

        extensionService.delete("my-extension");
        extensionService.delete("my-custom-extension");
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toFile()).doesNotExist();
        assertThat(extensionFolder.getRoot().toPath().resolve("my-custom-extension-1.0.0.jar").toFile()).doesNotExist();
    }

    @Test
    public void test_delete_extension_by_url() throws IOException {
        extensionFolder.newFile("my-extension-1.0.1.jar");
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath());
        Properties properties = PropertiesProvider.fromMap(new HashMap<>() {{
            put("extensionDelete", extensionFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toString());
        }});

        extensionService.deleteFromCli(properties);
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toFile()).doesNotExist();
    }
}
