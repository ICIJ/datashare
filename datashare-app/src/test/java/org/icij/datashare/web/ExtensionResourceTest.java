package org.icij.datashare.web;

import net.codestory.rest.FluentRestTest;
import org.icij.datashare.ExtensionService;
import org.icij.datashare.PluginService;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;

import static java.net.URLEncoder.encode;
import static org.fest.assertions.Assertions.assertThat;

import static org.junit.Assert.*;

public class ExtensionResourceTest extends AbstractProdWebServerTest  {

    @Rule public TemporaryFolder extensionFolder = new TemporaryFolder();

    @Test
    public void test_list_extension() {
        get("/api/extensions").should().respond(200)
                .haveType("application/json")
                .contain("my-extension")
                .contain("my-other-extension");
    }

    @Test
    public void test_install_extension_by_id(){
        put("/api/extensions/install?id=my-extension").should().respond(200);
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension.jar").toFile()).exists();
    }

    @Test
    public void test_install_extension_by_url() throws UnsupportedEncodingException {
        put("/api/extensions/install?url=" + encode(ClassLoader.getSystemResource("my-extension.jar").toString(), "utf-8")).should().respond(200);
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension.jar").toFile()).exists();
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
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension.jar").toFile()).exists();
        delete("/api/extensions/uninstall?id=my-extension").should().respond(200);
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension.jar").toFile()).doesNotExist();
    }

    @Test
    public void test_uninstall_unknown_extension() {
        delete("/api/extensions/uninstall?id=unknown_id").should().respond(404);
    }

    @Before
    public void setUp() {
        configure(routes -> routes.add(new ExtensionResource(new ExtensionService(extensionFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                "{\"id\":\"my-extension\", \"url\": \"" + ClassLoader.getSystemResource(("my-extension.jar")) + "\"}," +
                "{\"id\":\"my-other-extension\", \"url\": \"https://dummy.url\"}" +
                "]}").getBytes())))).filter(new LocalUserFilter(new PropertiesProvider())));
    }
}