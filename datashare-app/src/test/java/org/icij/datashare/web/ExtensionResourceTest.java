package org.icij.datashare.web;

import org.icij.datashare.ExtensionService;
import org.icij.datashare.ExtensionServiceTest;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Map;

import static java.net.URLEncoder.encode;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.ExtensionServiceTest.WRONG_SHA256;
import static org.icij.datashare.ExtensionServiceTest.aRegistryEntry;
import static org.icij.datashare.ExtensionServiceTest.aRegistryOf;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class ExtensionResourceTest extends AbstractProdWebServerTest  {
    @Mock JooqRepository jooqRepository;
    @Rule public TemporaryFolder extensionFolder = new TemporaryFolder();
    @Rule public TemporaryFolder otherFolder = new TemporaryFolder();

    @Test
    public void test_list_extension() {
        get("/api/extensions").should().respond(200)
                .haveType("application/json")
                .contain("my-extension")
                .contain("my-other-extension");
    }

    @Test
    public void test_list_extension_with_regexp() {
        get("/api/extensions?filter=.*other.*").
                should().respond(200).contain("my-other-extension").
                should().not().contain("my-plugin");
    }

    @Test
    public void test_list_extension_with_possible_extension_upgrade() throws IOException {
        extensionFolder.getRoot().toPath().resolve("my-extension-0.9.0.jar").toFile().createNewFile();
        extensionFolder.getRoot().toPath().resolve("my-extension-1.0.0.jar").toFile().createNewFile();
        get("/api/extensions").
                should().contain("\"installed\":true").contain("\"version\":\"1.0.1\"").contain("\"version\":\"1.0.0\"")
                        .contain("\"id\":\"my-extension\"").contain("\"description\":\"Description of my extension\"").should().not().contain("\"version\":\"0.9.0\"");
    }

    @Test
    public void test_install_extension_by_id(){
        put("/api/extensions/install?id=my-extension").should().respond(200);
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toFile()).exists();
    }

    @Test
    public void test_install_extension_by_registry_url() throws UnsupportedEncodingException {
        put("/api/extensions/install?url=" + encode(ClassLoader.getSystemResource("my-extension-1.0.1.jar").toString(), "utf-8")).should().respond(200);
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toFile()).exists();
    }

    @Test
    public void test_install_extension_by_file_url_out_of_registry_is_rejected() throws UnsupportedEncodingException {
        put("/api/extensions/install?url=" + encode("file:///etc/passwd", "utf-8")).should().respond(400).contain("not a url of the registry");
        assertThat(extensionFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_extension_by_url_out_of_registry_is_rejected_even_with_valid_id() throws UnsupportedEncodingException {
        put("/api/extensions/install?id=my-extension&url=" + encode("https://dummy.url/other/my-extension-1.0.1.jar", "utf-8")).should().respond(400).contain("not a url of the registry");
        assertThat(extensionFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_extension_by_http_url_is_rejected() throws UnsupportedEncodingException {
        put("/api/extensions/install?url=" + encode("http://dummy.url/my-extension-1.0.1.jar", "utf-8")).should().respond(400).contain("not a url of the registry");
        assertThat(extensionFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_extension_by_https_url_out_of_registry_is_rejected() throws UnsupportedEncodingException {
        put("/api/extensions/install?url=" + encode("https://localhost/my-extension-1.0.1.jar", "utf-8")).should().respond(400).contain("not a url of the registry");
        assertThat(extensionFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_extension_with_sha256_mismatch_responds_502() throws IOException {
        File tampered = otherFolder.newFile("tampered-extension-1.0.0.jar");
        configureWith(ExtensionServiceTest.createExtensionService(extensionFolder.getRoot().toPath(),
                aRegistryOf(aRegistryEntry("tampered-extension", tampered.toURI().toURL(), WRONG_SHA256))));

        put("/api/extensions/install?id=tampered-extension").should().respond(502).contain("sha256 mismatch");
        assertThat(extensionFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_extension_with_failing_download_responds_502() throws IOException {
        File missing = new File(otherFolder.getRoot(), "missing-extension-1.0.0.jar");
        configureWith(ExtensionServiceTest.createExtensionService(extensionFolder.getRoot().toPath(),
                aRegistryOf(aRegistryEntry("missing-extension", missing.toURI().toURL()))));

        put("/api/extensions/install?id=missing-extension").should().respond(502).contain("download failed");
        assertThat(extensionFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_extension_with_local_failure_responds_500_without_server_path() {
        File missingDir = new File(otherFolder.getRoot(), "missing-extensions-dir");
        configureWith(ExtensionServiceTest.createExtensionService(missingDir.toPath(),
                aRegistryOf(aRegistryEntry("my-extension", ClassLoader.getSystemResource("my-extension-1.0.1.jar")))));

        put("/api/extensions/install?id=my-extension").should().respond(500).contain("install failed")
                .should().not().contain("missing-extensions-dir");
    }

    @Test
    public void test_install_extension_with_blank_url_uses_id() {
        put("/api/extensions/install?id=my-extension&url=").should().respond(200);
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toFile()).exists();
    }

    @Test
    public void test_install_extension_with_malformed_url_is_rejected_even_with_valid_id() throws UnsupportedEncodingException {
        put("/api/extensions/install?id=my-extension&url=" + encode("htps:/typo", "utf-8")).should().respond(400).contain("malformed url");
        assertThat(extensionFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_extension_by_http_url_is_rejected_with_allow_insecure_addon_url_install() throws UnsupportedEncodingException {
        configureWith(new ExtensionService(new PropertiesProvider(Map.of(
                "extensionsDir", extensionFolder.getRoot().getPath(),
                "allowInsecureAddonUrlInstall", "true"))));

        put("/api/extensions/install?url=" + encode("http://dummy.url/my-extension-1.0.1.jar", "utf-8")).should().respond(400).contain("must be https");
        assertThat(extensionFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_extension_with_no_parameter() {
        put("/api/extensions/install").should().respond(400).contain("id or url parameter is required");
    }

    @Test
    public void test_install_unknown_extension() {
        put("/api/extensions/install?id=unknown_id").should().respond(404);
    }

    @Test
    public void test_uninstall_extension() {
        put("/api/extensions/install?id=my-extension").should().respond(200);
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toFile()).exists();
        delete("/api/extensions/uninstall?id=my-extension").should().respond(204);
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toFile()).doesNotExist();
    }

    @Test
    public void test_uninstall_unknown_extension() {
        delete("/api/extensions/uninstall?id=unknown_id").should().respond(204);
    }

    private void configureWith(ExtensionService extensionService) {
        configure(routes -> routes.add(new ExtensionResource(extensionService))
                .filter(new LocalUserFilter(new PropertiesProvider(), jooqRepository)));
    }

    @Before
    public void setUp() {
        initMocks(this);
        when(jooqRepository.getProjects()).thenReturn(new ArrayList<>());
        configure(routes -> routes.add(new ExtensionResource(
            ExtensionServiceTest.createExtensionService(extensionFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                "{\"id\":\"my-extension\",\"name\":\"My extension\",\"description\":\"Description of my extension\",\"version\":\"1.0.1\",\"type\":\"WEB\",\"url\": \"" + ClassLoader.getSystemResource(("my-extension-1.0.1.jar")) + "\"}," +
                "{\"id\":\"my-other-extension\",\"name\":\"My other extension\",\"description\":\"Description of my other extension\",\"type\":\"WEB\",\"url\": \"https://dummy.url/foo.jar\"}" +
                "]}").getBytes())))).filter(new LocalUserFilter(new PropertiesProvider(), jooqRepository)));
    }
}
