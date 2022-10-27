package org.icij.datashare.web;

import net.codestory.http.filters.basic.BasicAuthFilter;
import org.icij.datashare.PropertiesProvider;
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
import static org.icij.datashare.session.DatashareUser.local;
import static org.icij.datashare.session.DatashareUser.singleUser;

public class SettingsResourceTest extends AbstractProdWebServerTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Test
    public void test_patch_configuration() throws IOException {
        File settings = folder.newFile("file.settings");
        Files.write(settings.toPath(), asList("foo=doe", "bar=baz"));
        configure(routes -> routes.add(new SettingsResource(new PropertiesProvider(settings.getAbsolutePath()))).
                filter(new BasicAuthFilter("/", "icij", singleUser(local()))));

        patch("/api/settings", "{\"data\": {\"foo\": \"qux\", \"xyzzy\":\"fred\"}}").
                withPreemptiveAuthentication("local", "pass").should().respond(200);

        Properties properties = new PropertiesProvider(settings.getAbsolutePath()).getProperties();
        assertThat(properties).includes(entry("foo", "qux"), entry("bar", "baz"), entry("xyzzy", "fred"));
    }

    @Test
    public void test_patch_configuration_with_no_config_file() {
        configure(routes -> routes.add(new SettingsResource(new PropertiesProvider("/unwritable.conf"))).
                filter(new BasicAuthFilter("/", "icij", singleUser(local()))));

        patch("/api/settings", "{\"data\": {\"foo\": \"qux\", \"xyzzy\":\"fred\"}}").
                withPreemptiveAuthentication("local", "pass").should().respond(404);
    }

    @Test
    public void test_patch_configuration_should_answer_403_in_server_mode() {
        PropertiesProvider properties = new PropertiesProvider(new HashMap<String, String>() {{
            put("mode", "SERVER");
        }});
        configure(routes -> routes.add(new SettingsResource(properties)).filter(new BasicAuthFilter("/", "icij", singleUser(local()))));

        patch("/api/settings", "{\"data\": {\"foo\": \"qux\", \"xyzzy\":\"fred\"}}").
            withPreemptiveAuthentication("local", "pass").should().respond(403);
    }

    @Test
    public void test_list_ocr_languages() {
        PropertiesProvider properties = new PropertiesProvider();
        configure(routes -> routes.add(new SettingsResource(properties)));

        get("/api/settings/ocr/languages")
                .should()
                .respond(200);
    }

}
