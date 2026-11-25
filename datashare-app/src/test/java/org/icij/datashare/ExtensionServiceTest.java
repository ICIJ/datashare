package org.icij.datashare;

import java.io.InputStream;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.icij.datashare.Deliverable.Type.WEB;
import static org.icij.datashare.PropertiesProvider.ALLOW_INSECURE_ADDON_URL_INSTALL_OPT;
import static org.icij.datashare.PropertiesProvider.EXTENSIONS_DIR_OPT;

public class ExtensionServiceTest {
    @Rule public TemporaryFolder extensionFolder = new TemporaryFolder();
    @Rule public TemporaryFolder otherFolder = new TemporaryFolder();
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();
    public static final String WRONG_SHA256 = "00";
    private static final String SHA256_OF_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

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
            put(EXTENSIONS_DIR_OPT, extensionFolder.getRoot().getPath());
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
        URL url = aJarContaining("abc");
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath());

        withTemporaryDirectory(temporaryFolder.getRoot(), () -> extensionService.downloadAndInstall(url));

        assertThat(temporaryFolder.getRoot().list()).isEmpty();
        assertThat(installedJar()).exists();
        assertThat(installedJar().canExecute()).isTrue();
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
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension-1.2.4").toFile().canExecute()).isTrue();
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

    @Test
    public void test_install_from_registry_url_with_matching_sha256() throws Exception {
        URL url = aJarContaining("abc");
        ExtensionService extensionService = anExtensionServiceWith(aRegistryEntry(url, SHA256_OF_ABC));

        extensionService.downloadAndInstallFromRemote(url);

        assertThat(installedJar()).exists();
    }

    @Test
    public void test_install_from_registry_url_with_wrong_sha256_is_refused() throws Exception {
        URL url = aJarContaining("tampered");
        ExtensionService extensionService = anExtensionServiceWith(aRegistryEntry(url, SHA256_OF_ABC));

        Extension.InvalidDeliverableException refused = assertThrows(Extension.InvalidDeliverableException.class,
                () -> extensionService.downloadAndInstallFromRemote(url));

        assertThat(refused.getMessage()).contains("sha256 mismatch");
        assertThat(extensionFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_from_id_with_matching_sha256() throws Exception {
        ExtensionService extensionService = anExtensionServiceWith(aRegistryEntry(aJarContaining("abc"), SHA256_OF_ABC));

        extensionService.downloadAndInstall("my-extension");

        assertThat(installedJar()).exists();
    }

    @Test
    public void test_install_from_id_with_wrong_sha256_is_refused() throws Exception {
        ExtensionService extensionService =
                anExtensionServiceWith(aRegistryEntry(aJarContaining("tampered"), SHA256_OF_ABC));

        Extension.InvalidDeliverableException refused = assertThrows(Extension.InvalidDeliverableException.class,
                () -> extensionService.downloadAndInstall("my-extension"));

        assertThat(refused.getMessage()).contains("sha256 mismatch");
        assertThat(extensionFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_from_url_out_of_registry_is_refused() throws Exception {
        URL url = aJarContaining("abc");
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath());

        DeliverableService.UrlNotAllowedException refused = assertThrows(
                DeliverableService.UrlNotAllowedException.class,
                () -> extensionService.downloadAndInstallFromRemote(url));

        assertThat(refused.getMessage()).contains("not a url of the registry");
        assertThat(extensionFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_from_other_release_on_registry_host_is_refused() throws Exception {
        ExtensionService extensionService = anExtensionServiceWith(aRegistryEntry("my-extension",
                new URL("https://github.com/ICIJ/my-extension/releases/download/1.0.1/my-extension-1.0.1.jar")));

        assertThrows(DeliverableService.UrlNotAllowedException.class,
                () -> extensionService.downloadAndInstallFromRemote(
                        new URL("https://github.com/attacker/x/releases/download/v1/evil-1.0.0.jar")));
    }

    @Test
    public void test_refusal_message_has_no_control_characters() throws Exception {
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath());

        DeliverableService.UrlNotAllowedException refused = assertThrows(
                DeliverableService.UrlNotAllowedException.class,
                () -> extensionService.downloadAndInstallFromRemote(
                        new URL("https://example.org/a\r\nb\u0085c\u2028d")));

        assertThat(refused.getMessage()).contains("https://example.org/a__b_c_d");
    }

    @Test
    public void test_unregistered_https_url_is_refused_by_default() throws Exception {
        ExtensionService extensionService = new ExtensionService(new PropertiesProvider(new HashMap<>() {{
            put(EXTENSIONS_DIR_OPT, extensionFolder.getRoot().getPath());
        }}));

        assertThat(extensionService.isAllowedUnregistered(new URL("https://example.org/ext-1.0.0.jar"))).isFalse();
    }

    @Test
    public void test_unregistered_https_url_is_allowed_with_allow_insecure_addon_url_install() throws Exception {
        ExtensionService extensionService = anExtensionServiceAllowingInsecureUrls();

        assertThat(extensionService.isAllowedUnregistered(new URL("https://example.org/ext-1.0.0.jar"))).isTrue();
    }

    @Test
    public void test_unregistered_url_must_be_plain_https_even_with_allow_insecure_addon_url_install() throws Exception {
        ExtensionService extensionService = anExtensionServiceAllowingInsecureUrls();

        for (String url : List.of("http://example.org/ext-1.0.0.jar", "https://user@example.org/ext-1.0.0.jar",
                                  "file:///tmp/ext-1.0.0.jar", "jar:https://example.org/ext-1.0.0.jar!/",
                                  "https://example.org/a\r\nb.jar", "https://example.org/a\u0085b.jar")) {
            assertThat(extensionService.isAllowedUnregistered(new URL(url))).as(url).isFalse();
        }
    }

    @Test
    public void test_refusal_message_with_allow_insecure_addon_url_install_says_https_is_required() {
        ExtensionService extensionService = anExtensionServiceAllowingInsecureUrls();

        DeliverableService.UrlNotAllowedException refused = assertThrows(
                DeliverableService.UrlNotAllowedException.class,
                () -> extensionService.downloadAndInstallFromRemote(new URL("http://example.org/ext-1.0.0.jar")));

        assertThat(refused.getMessage()).contains("must be https");
    }

    @Test
    public void test_install_from_unregistered_url_when_allowed() throws Exception {
        URL url = aJarContaining("abc");
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath()) {
            @Override
            boolean isAllowedUnregistered(URL unregistered) {
                return true; // stands for the option being on, without needing an https server
            }
        };

        extensionService.downloadAndInstallFromRemote(url);

        assertThat(installedJar()).exists();
    }

    @Test
    public void test_allow_insecure_addon_url_install_must_be_true_or_false() {
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                () -> new ExtensionService(new PropertiesProvider(Map.of(
                        EXTENSIONS_DIR_OPT, extensionFolder.getRoot().getPath(),
                        ALLOW_INSECURE_ADDON_URL_INSTALL_OPT, "1"))));

        assertThat(invalid.getMessage()).contains("allowInsecureAddonUrlInstall must be true or false, got: 1");
    }

    @Test
    public void test_host_specific_extension_is_installed_without_sha256_check() throws Exception {
        // a url that is not a jar is a native binary: one hash cannot match every os/arch, so it is skipped
        File binary = otherFolder.newFile("my-binary");
        ExtensionService extensionService =
                anExtensionServiceWith(aRegistryEntry("my-binary", binary.toURI().toURL(), WRONG_SHA256));

        extensionService.downloadAndInstall("my-binary");

        assertThat(extensionFolder.getRoot().toPath().resolve("my-binary").toFile()).exists();
    }

    @Test
    public void test_install_keeps_an_extension_whose_name_only_shares_the_prefix() throws Exception {
        extensionFolder.newFile("my-extension-extra-2.0.0.jar");
        extensionFolder.newFile("my-extension-1.0.0.jar");
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath()) {
            @Override
            boolean isAllowedUnregistered(URL unregistered) {
                return true;
            }
        };

        extensionService.downloadAndInstallFromRemote(aJarContaining("abc"));

        assertThat(extensionFolder.getRoot().list()).containsOnly("my-extension-extra-2.0.0.jar", "my-extension-1.0.1.jar");
    }

    @Test
    public void test_concurrent_installs_of_the_same_extension_all_succeed() throws Exception {
        ExtensionService extensionService =
                anExtensionServiceWith(aRegistryEntry(aJarContaining("abc"), SHA256_OF_ABC));
        int concurrentInstalls = 8;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentInstalls);
        CountDownLatch allStartTogether = new CountDownLatch(1);
        try {
            List<Future<Void>> installs = new ArrayList<>();
            for (int i = 0; i < concurrentInstalls; i++) {
                installs.add(executor.submit(() -> {
                    allStartTogether.await();
                    extensionService.downloadAndInstall("my-extension");
                    return null;
                }));
            }
            allStartTogether.countDown();
            for (Future<Void> install : installs) {
                install.get(30, TimeUnit.SECONDS); // rethrows the failure of an install
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(extensionFolder.getRoot().list()).containsOnly("my-extension-1.0.1.jar");
        assertThat(Files.readString(installedJar().toPath())).isEqualTo("abc");
    }

    @Test
    public void test_download_gives_up_when_the_remote_never_answers() throws Exception {
        try (ServerSocket silentServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            URL url = new URL("http://127.0.0.1:" + silentServer.getLocalPort() + "/my-extension-1.0.1.jar");

            assertThrows(SocketTimeoutException.class, () -> Extension.openStream(url, 1_000, 200).read());
        }
    }

    @Test
    public void test_download_at_size_limit_succeeds() throws Exception {
        URL url = aJarContaining("0123456789");

        File downloaded = new Extension(url).download(url, ".jar", 10);

        assertThat(downloaded).exists();
        downloaded.delete();
    }

    @Test
    public void test_download_over_size_limit_is_refused_and_leaves_no_temporary_file() throws Exception {
        URL url = aJarContaining("0123456789");

        withTemporaryDirectory(temporaryFolder.getRoot(), () -> {
            Extension.InvalidDeliverableException refused = assertThrows(Extension.InvalidDeliverableException.class,
                    () -> new Extension(url).download(url, ".jar", 9));
            assertThat(refused.getMessage()).contains("size limit");
        });

        assertThat(temporaryFolder.getRoot().list()).isEmpty();
    }

    /** Runs the code with java.io.tmpdir pointing at a directory owned by the test, so leftovers are visible. */
    public static void withTemporaryDirectory(File temporaryDirectory, ThrowingRunnable code) throws Exception {
        String systemTemporaryDirectory = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", temporaryDirectory.getPath());
        try {
            code.run();
        } catch (Exception | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("unexpected throwable in test code", t);
        } finally {
            System.setProperty("java.io.tmpdir", systemTemporaryDirectory);
        }
    }

    private URL aJarContaining(String content) throws IOException {
        File jar = otherFolder.newFile("my-extension-1.0.1.jar");
        Files.writeString(jar.toPath(), content);
        return jar.toURI().toURL();
    }

    private static String aRegistryEntry(URL url, String sha256) {
        return aRegistryEntry("my-extension", url, sha256);
    }

    public static String aRegistryEntry(String id, URL url) {
        return aRegistryEntry(id, url, null);
    }

    public static String aRegistryEntry(String id, URL url, String sha256) {
        return "{\"id\":\"" + id + "\", \"url\": \"" + url + "\"" +
               (sha256 == null ? "" : ", \"sha256\": \"" + sha256 + "\"") + "}";
    }

    public static InputStream aRegistryOf(String... registryEntries) {
        return new ByteArrayInputStream(
                ("{\"deliverableList\": [" + String.join(",", registryEntries) + "]}").getBytes());
    }

    private ExtensionService anExtensionServiceWith(String... registryEntries) {
        return new ExtensionService(extensionFolder.getRoot().toPath(), aRegistryOf(registryEntries));
    }

    private ExtensionService anExtensionServiceAllowingInsecureUrls() {
        return new ExtensionService(new PropertiesProvider(new HashMap<>() {{
            put(EXTENSIONS_DIR_OPT, extensionFolder.getRoot().getPath());
            put(ALLOW_INSECURE_ADDON_URL_INSTALL_OPT, "true");
        }}));
    }

    private File installedJar() {
        return extensionFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toFile();
    }
}
