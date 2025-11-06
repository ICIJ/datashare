package org.icij.datashare.web;


import net.codestory.http.WebServer;
import net.codestory.http.io.ClassPaths;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import org.apache.commons.io.FileUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.session.LocalUserFilter;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static java.nio.file.Files.copy;
import static java.util.Arrays.asList;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

public class RootResourcePluginTest implements FluentRestTest {
    @Mock JooqRepository jooqRepository;
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
        openMocks(this);
        when(jooqRepository.getProjects()).thenReturn(new ArrayList<>());
        propertiesProvider = new PropertiesProvider(new HashMap<>() {{
            put("pluginsDir", folder.getRoot().toString());
        }});
        server.configure(routes -> {
            routes.add(new RootResource(propertiesProvider))
                    .bind("/plugins", folder.getRoot())
                    .filter(new LocalUserFilter(new PropertiesProvider(), jooqRepository));
        });
    }

    @Test
    public void test_get_with_no_plugin_directory() {
        server.configure(routes -> routes.add(RootResource.class));
        get("/").should().respond(200).contain("datashare-client");
        get("").should().respond(200).contain("datashare-client");
    }

    @Test
    public void test_get_with_missing_plugin_directory() {
        propertiesProvider = new PropertiesProvider(Map.of("pluginsDir", "/something/missing/"));
        server.configure(routes -> routes.add(new RootResource(propertiesProvider)));
        get("/").should().respond(200).contain("datashare-client");
        get("").should().respond(200).contain("datashare-client");

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

        get("/").should().respond(200).contain("<script src=\"/plugins/my_plugin/index.js\" defer></script></body>");
        get("/plugins/my_plugin/index.js").should().respond(200);
    }

    @Test
    public void test_get_with_plugin_directory_that_contains_a_folder_with_package_json_file() throws Exception {
        Path pluginPath = folder.newFolder("my_plugin").toPath();
        Files.write(pluginPath.resolve("package.json"), asList(
                "{",
                "  \"main\": \"app.js\",",
                "  \"vue\": {\"filenameHashing\": false  },",
                "  \"files\": [\"dist/{css,js}/*.{css,js,map}\"]",
                "}"
        ));
        pluginPath.resolve("app.js").toFile().createNewFile();

        get("/").should().respond(200).contain("<script src=\"/plugins/my_plugin/app.js\" defer></script></body>");
        get("/plugins/my_plugin/app.js").should().respond(200);
    }

    @Test
    public void test_get_with_plugin_directory_that_contains_a_folder_with_package_json_file_with_private_and_projects_ok() throws Exception {
        Path pluginPath = folder.newFolder("my_plugin").toPath();
        Files.write(pluginPath.resolve("package.json"), asList(
                "{",
                "  \"main\": \"app.js\",",
                "  \"vue\": {\"filenameHashing\": false  },",
                "  \"files\": [\"dist/{css,js}/*.{css,js,map}\"],",
                "  \"private\": true,",
                "  \"datashare\": {",
                "    \"projects\": [\"local-datashare\", \"Tata\"]",
                "   }",
                "}"
        ));
        pluginPath.resolve("app.js").toFile().createNewFile();

        get("/").should().respond(200).contain("<script src=\"/plugins/my_plugin/app.js\" defer></script></body>");
        get("/plugins/my_plugin/app.js").should().respond(200);
    }

    @Test
    public void test_get_with_plugin_directory_that_contains_a_folder_with_package_json_file_with_private_and_projects_without_current_user() throws Exception {
        server.configure(routes -> routes.add(new RootResource(propertiesProvider)).bind("/plugins", folder.getRoot()));
        Path pluginPath = folder.newFolder("my_plugin").toPath();
        Files.write(pluginPath.resolve("package.json"), asList(
                "{",
                "  \"main\": \"app.js\",",
                "  \"vue\": {\"filenameHashing\": false  },",
                "  \"files\": [\"dist/{css,js}/*.{css,js,map}\"],",
                "  \"private\": true,",
                "  \"datashare\": {",
                "    \"projects\": [\"local-datashare\", \"Tata\"]",
                "   }",
                "}"
        ));
        pluginPath.resolve("app.js").toFile().createNewFile();

        get("/").should().respond(200).not().contain("<script src=\"/plugins/my_plugin/app.js\" defer></script></body>");
        get("/plugins/my_plugin/app.js").should().respond(200);
    }

    @Test
    public void test_get_with_plugin_directory_that_contains_a_folder_with_package_json_file_and_css() throws Exception {
        Path pluginPath = folder.newFolder("my_plugin").toPath();
        Files.write(pluginPath.resolve("package.json"), asList(
                "{",
                "  \"style\": \"app.css\"",
                "}"
        ));
        pluginPath.resolve("app.css").toFile().createNewFile();

        get("/").should().respond(200).contain("<link rel=\"stylesheet\" href=\"/plugins/my_plugin/app.css\"></head>");
        get("/plugins/my_plugin/app.css").should().respond(200);
    }

    @Override
    public int port() { return server.port();}

    @After
    public void tearDown() throws IOException {
        Path pluginsDir = appFolder.getRoot().toPath().resolve("app").resolve("plugins");
        if (pluginsDir.toFile().exists()) FileUtils.forceDelete(pluginsDir.toFile());
    }
}
