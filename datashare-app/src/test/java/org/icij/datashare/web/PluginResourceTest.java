package org.icij.datashare.web;

import org.icij.datashare.PluginService;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;

import static org.fest.assertions.Assertions.assertThat;

public class PluginResourceTest extends AbstractProdWebServerTest {
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
    public void test_install_plugin() {
        put("/api/plugins/install/my-plugin").should().respond(200);
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").toFile()).exists();
    }

    @Test
    public void test_install_unknown_plugin() {
        put("/api/plugins/install/unknown_id").should().respond(404);
    }

    @Before
    public void setUp() {
        configure(routes -> {
            routes.add(new PluginResource(new PluginService(pluginFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"pluginList\": [" +
            "{\"id\":\"my-plugin\", \"url\": \"" + ClassLoader.getSystemResource("my-plugin.tgz")+ "\"}," +
            "{\"id\":\"my-other-plugin\", \"url\": \"https://dummy.url\"}" +
            "]}").getBytes())))).filter(new LocalUserFilter(new PropertiesProvider()));
        });
    }
}
