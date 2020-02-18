package org.icij.datashare.web;


import net.codestory.http.WebServer;
import net.codestory.http.io.ClassPaths;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import org.apache.commons.io.FileUtils;
import org.icij.datashare.PropertiesProvider;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;

import static java.nio.file.Files.copy;
import static java.util.Arrays.asList;

public class RootResourcePluginTest implements FluentRestTest {
    @ClassRule public static TemporaryFolder appFolder = new TemporaryFolder();
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static WebServer server;
    PropertiesProvider propertiesProvider;

    @BeforeClass
    public static void beforeClass() throws Exception {
        appFolder.newFolder("app");
        copy(Paths.get(ClassPaths.getResource("app/index.html").getPath()),
                appFolder.getRoot().toPath().resolve("app").resolve("index.html"));
        server = new WebServer() {
            @Override
            protected Env createEnv() {
                return Env.dev(appFolder.getRoot());
            }
        }.startOnRandomPort();
    }

    @Before
    public void setUp() {
        propertiesProvider = new PropertiesProvider(new HashMap<String, String>() {{
            put("pluginsDir", folder.getRoot().toString());
        }});
        server.configure(routes -> routes.add(new RootResource(propertiesProvider)).bind("/plugins", folder.getRoot()));
    }

    @Test
    public void test_get_with_no_plugin_directory() {
        server.configure(routes -> routes.add(RootResource.class));
        get("/").should().respond(200).contain("datashare-client");
        get("").should().respond(200).contain("datashare-client");
    }

    @Test
    public void test_invalid_folder_should_throw_error() {
        server.configure(routes -> routes.add(new RootResource(new PropertiesProvider(new HashMap<String, String>() {{
            put("pluginsDir", "unknown");
        }}))));
        get("/").should().respond(500);
    }

    @Test
    public void test_get_with_empty_plugin_directory() {
        get("/").should().respond(200).contain("</div></body>");
        get("").should().respond(200).contain("</div></body>");
    }

    @Test
    public void test_get_with_plugin_directory_that_contains_an_empty_folder() throws IOException {
        folder.newFolder("my_plugin");
        get("/").should().respond(200).contain("</div></body>");
    }

    @Test
    public void test_get_with_plugin_directory_that_contains_a_folder_with_nor_indexjs_package_json() throws IOException {
        folder.newFolder("my_plugin").toPath().resolve("my_file").toFile().createNewFile();
        get("/").should().respond(200).contain("</div></body>");
    }

    @Test
    public void test_get_with_plugin_directory_that_contains_a_folder_with_indexjs() throws Exception {
        folder.newFolder("my_plugin").toPath().resolve("index.js").toFile().createNewFile();

        get("/").should().respond(200).contain("<script src=\"/plugins/my_plugin/index.js\"></script></body>");
        get("/plugins/my_plugin/index.js").should().respond(200);
    }

    @Test
    public void test_get_with_plugin_directory_that_contains_a_folder_with_package_json_file() throws Exception {
        Path pluginPath = folder.newFolder("my_plugin").toPath();
        Files.write(pluginPath.resolve("package.json"), asList(
                "{",
                "  \"main\": \"app.js\"",
                "}"
        ));
        pluginPath.resolve("app.js").toFile().createNewFile();

        get("/").should().respond(200).contain("<script src=\"/plugins/my_plugin/app.js\"></script></body>");
        get("/plugins/my_plugin/app.js").should().respond(200);
    }

    @Override
    public int port() { return server.port();}

    @After
    public void tearDown() throws IOException {
        Path pluginsDir = appFolder.getRoot().toPath().resolve("app").resolve("plugins");
        if (pluginsDir.toFile().exists()) FileUtils.forceDelete(pluginsDir.toFile());
    }
}
