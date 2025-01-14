package org.icij.datashare.web;

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
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;

import static java.net.URLEncoder.encode;
import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class ExtensionResourceTest extends AbstractProdWebServerTest  {
    @Mock JooqRepository jooqRepository;
    @Rule public TemporaryFolder extensionFolder = new TemporaryFolder();

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
    public void test_install_extension_by_url() throws UnsupportedEncodingException {
        put("/api/extensions/install?url=" + encode(ClassLoader.getSystemResource("my-extension-1.0.1.jar").toString(), "utf-8")).should().respond(200);
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension-1.0.1.jar").toFile()).exists();
    }

    @Test
    public void test_install_extension_with_no_parameter() {
        put("/api/extensions/install").should().respond(400);
    }

    @Test
    public void test_install_unknown_extension() {
        put("/api/extensions/install/unknown_id").should().respond(404);
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
