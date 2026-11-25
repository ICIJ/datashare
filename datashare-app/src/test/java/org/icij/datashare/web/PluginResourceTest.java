package org.icij.datashare.web;

import org.icij.datashare.PluginService;
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
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;

import static java.net.URLEncoder.encode;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.ExtensionServiceTest.WRONG_SHA256;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class PluginResourceTest extends AbstractProdWebServerTest {
    @Mock JooqRepository jooqRepository;
    @Rule public TemporaryFolder pluginFolder = new TemporaryFolder();

    @Test
    public void test_list_plugins() {
        get("/api/plugins").should().respond(200).
                haveType("application/json").
                contain("my-plugin").
                contain("my-other-plugin");
    }

    @Test
    public void test_list_plugins_with_regexp() {
        get("/api/plugins?filter=.*other.*").
                should().respond(200).contain("my-other-plugin").
                should().not().contain("my-plugin");
    }

    @Test
    public void test_install_plugin_by_id() {
        put("/api/plugins/install?id=my-plugin").should().respond(200);
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").toFile()).exists();
    }

    @Test
    public void test_install_plugin_by_registry_url() throws UnsupportedEncodingException {
        put("/api/plugins/install?url=" + encode(ClassLoader.getSystemResource("my-plugin.tgz").toString(), "utf-8")).should().respond(200);
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").toFile()).exists();
    }

    @Test
    public void test_install_plugin_by_file_url_out_of_registry_is_rejected() throws UnsupportedEncodingException {
        put("/api/plugins/install?url=" + encode("file:///etc/passwd", "utf-8")).should().respond(400).contain("not a url of the registry");
        assertThat(pluginFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_plugin_by_url_out_of_registry_is_rejected_even_with_valid_id() throws UnsupportedEncodingException {
        put("/api/plugins/install?id=my-plugin&url=" + encode("https://dummy.url/other/my-plugin.tgz", "utf-8")).should().respond(400).contain("not a url of the registry");
        assertThat(pluginFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_plugin_by_http_url_is_rejected() throws UnsupportedEncodingException {
        put("/api/plugins/install?url=" + encode("http://dummy.url/my-plugin.tgz", "utf-8")).should().respond(400).contain("not a url of the registry");
        assertThat(pluginFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_plugin_by_https_url_out_of_registry_is_rejected() throws UnsupportedEncodingException {
        put("/api/plugins/install?url=" + encode("https://localhost/my-plugin.tgz", "utf-8")).should().respond(400).contain("not a url of the registry");
        assertThat(pluginFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_plugin_with_sha256_mismatch_responds_502() {
        put("/api/plugins/install?id=tampered-plugin").should().respond(502).contain("sha256 mismatch");
        assertThat(pluginFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_plugin_with_blank_url_uses_id() {
        put("/api/plugins/install?id=my-plugin&url=").should().respond(200);
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").toFile()).exists();
    }

    @Test
    public void test_install_plugin_with_malformed_url_is_rejected_even_with_valid_id() throws UnsupportedEncodingException {
        put("/api/plugins/install?id=my-plugin&url=" + encode("htps:/typo", "utf-8")).should().respond(400).contain("malformed url");
        assertThat(pluginFolder.getRoot().list()).isEmpty();
    }

    @Test
    public void test_install_plugin_with_no_parameter() {
        put("/api/plugins/install").should().respond(400).contain("id or url parameter is required");
    }

    @Test
    public void test_install_unknown_plugin() {
        put("/api/plugins/install?id=unknown_id").should().respond(404);
    }

    @Test
    public void test_uninstall_plugin() {
        put("/api/plugins/install?id=my-plugin").should().respond(200);
        delete("/api/plugins/uninstall?id=my-plugin").should().respond(204);
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").toFile()).doesNotExist();
    }

    @Test
    public void test_uninstall_unknown_plugin() {
        delete("/api/plugins/uninstall?id=unknown_id").should().respond(204);
    }

    @Before
    public void setUp() {
        initMocks(this);
        when(jooqRepository.getProjects()).thenReturn(new ArrayList<>());
        configure(routes -> {
            routes
                    .add(new PluginResource(new PluginService(pluginFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                            "{\"id\":\"my-plugin\",\"name\":\"My plugin\",\"description\":\"Description of my plugin\", \"url\": \"" + ClassLoader.getSystemResource("my-plugin.tgz") + "\"}," +
                            "{\"id\":\"tampered-plugin\",\"name\":\"Tampered plugin\",\"description\":\"Description of tampered plugin\",\"sha256\":\"" + WRONG_SHA256 + "\", \"url\": \"" + ClassLoader.getSystemResource("my-plugin.zip") + "\"}," +
                            "{\"id\":\"my-other-plugin\",\"name\":\"My other plugin\",\"description\":\"Description of my other plugin  \", \"url\": \"https://dummy.url\"}" +
                            "]}").getBytes()))))
                    .filter(new LocalUserFilter(new PropertiesProvider(), jooqRepository));
        });
    }
}
