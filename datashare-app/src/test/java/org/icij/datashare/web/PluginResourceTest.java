package org.icij.datashare.web;

import org.icij.datashare.PluginService;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PluginResourceTest extends AbstractProdWebServerTest {
    @Rule public TemporaryFolder pluginFolder = new TemporaryFolder();
    @Test
    public void test_list_plugins() {
        get("/api/plugins").should().respond(200).
                haveType("application/json").
                contain("my-plugin-foo").
                contain("my-plugin-bar").
                contain("my-plugin-baz");
    }

    @Test
    public void test_list_plugins_with_regexp() {
        get("/api/plugins?filter=.*foo").
                should().respond(200).contain("my-plugin-foo").
                should().not().contain("my-plugin-bar").
                should().not().contain("my-plugin-baz");
    }

    @Before
    public void setUp() {
        configure(routes -> {
            routes.add(new PluginResource(new PluginService())).
                    filter(new LocalUserFilter(new PropertiesProvider()));
        });
    }
}
