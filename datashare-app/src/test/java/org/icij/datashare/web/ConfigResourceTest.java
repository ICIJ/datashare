package org.icij.datashare.web;

import net.codestory.http.filters.basic.BasicAuthFilter;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.session.HashMapUser;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Properties;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.session.HashMapUser.local;
import static org.icij.datashare.session.HashMapUser.singleUser;

public class ConfigResourceTest extends AbstractProdWebServerTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Test
    public void test_get_indices_from_user_map_with_no_indices() {
        configure(routes -> routes.add(new ConfigResource(new PropertiesProvider())).
                filter(new BasicAuthFilter("/", "icij", singleUser("soline"))));

        get("/api/config").withPreemptiveAuthentication("soline", "pass").should().respond(200).
                haveType("application/json").contain("[\"soline-datashare\"]");
    }
    @Test
    public void test_get_indices_from_user_map() {
        configure(routes -> routes.add(new ConfigResource(new PropertiesProvider())).
                filter(new BasicAuthFilter("/", "icij", singleUser(new HashMapUser(new HashMap<String, String>() {{
                    put("uid", "soline");
                    put("datashare_indices", "[\"foo\",\"bar\"]");
                }})))));

        get("/api/config").withPreemptiveAuthentication("soline", "pass").should().respond(200).
                haveType("application/json").contain("[\"soline-datashare\",\"foo\",\"bar\"]");
    }
    @Test
    public void test_get_indices_from_local_user() {
        configure(routes -> routes.add(new ConfigResource(new PropertiesProvider())).
                filter(new BasicAuthFilter("/", "icij", singleUser(local()))));

        get("/api/config").withPreemptiveAuthentication("local", "pass").should().respond(200).
                haveType("application/json").contain("[\"local-datashare\"]");
    }

    @Test
    public void test_patch_configuration() throws IOException {
        File configFile = folder.newFile("file.config");
        Files.write(configFile.toPath(), asList("foo=doe", "bar=baz"));
        configure(routes -> routes.add(new ConfigResource(new PropertiesProvider(configFile.getAbsolutePath()))).
                filter(new BasicAuthFilter("/", "icij", singleUser(local()))));

        patch("/api/config", "{\"data\": {\"foo\": \"qux\", \"xyzzy\":\"fred\"}}").
                withPreemptiveAuthentication("local", "pass").should().respond(200);

        Properties properties = new PropertiesProvider(configFile.getAbsolutePath()).getProperties();
        assertThat(properties).includes(entry("foo", "qux"), entry("bar", "baz"), entry("xyzzy", "fred"));
    }

    @Test
    public void test_patch_configuration_with_no_config_file() {
        configure(routes -> routes.add(new ConfigResource(new PropertiesProvider("/unwritable.conf"))).
                filter(new BasicAuthFilter("/", "icij", singleUser(local()))));

        patch("/api/config", "{\"data\": {\"foo\": \"qux\", \"xyzzy\":\"fred\"}}").
                withPreemptiveAuthentication("local", "pass").should().respond(404);
    }
}
