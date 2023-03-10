package org.icij.datashare.web;

import net.codestory.http.filters.basic.BasicAuthFilter;
import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.parser.external.ExternalParser;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.tasks.DelApiKeyTask;
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
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class SettingsResourceTest extends AbstractProdWebServerTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Test
    public void test_patch_configuration() throws IOException {
        File settings = folder.newFile("file.settings");
        Files.write(settings.toPath(), asList("foo=doe", "bar=baz"));
        configure(routes -> routes.add(new SettingsResource(new PropertiesProvider(settings.getAbsolutePath()), new TesseractOCRParser())).
                filter(new BasicAuthFilter("/", "icij", singleUser(local()))));

        patch("/api/settings", "{\"data\": {\"foo\": \"qux\", \"xyzzy\":\"fred\"}}").
                withPreemptiveAuthentication("local", "pass").should().respond(200);

        Properties properties = new PropertiesProvider(settings.getAbsolutePath()).getProperties();
        assertThat(properties).includes(entry("foo", "qux"), entry("bar", "baz"), entry("xyzzy", "fred"));
    }

    @Test
    public void test_patch_configuration_with_no_config_file() {
        configure(routes -> routes.add(new SettingsResource(new PropertiesProvider("/unwritable.conf"), new TesseractOCRParser())).
                filter(new BasicAuthFilter("/", "icij", singleUser(local()))));

        patch("/api/settings", "{\"data\": {\"foo\": \"qux\", \"xyzzy\":\"fred\"}}").
                withPreemptiveAuthentication("local", "pass").should().respond(404);
    }

    @Test
    public void test_patch_configuration_should_answer_403_in_server_mode() {
        PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<String, String>() {{
            put("mode", "SERVER");
        }});
        configure(routes -> routes.add(new SettingsResource(propertiesProvider, new TesseractOCRParser())).filter(new BasicAuthFilter("/", "icij", singleUser(local()))));

        patch("/api/settings", "{\"data\": {\"foo\": \"qux\", \"xyzzy\":\"fred\"}}").
            withPreemptiveAuthentication("local", "pass").should().respond(403);
    }

    @Test
    public void test_list_ocr_languages() throws TikaConfigException {
        TesseractOCRParser mock = mock(TesseractOCRParser.class);
        when(mock.hasTesseract()).thenReturn(true);
        PropertiesProvider propertiesProvider = new PropertiesProvider();
        configure(routes -> routes.add(new SettingsResource(propertiesProvider, mock)));

        get("/api/settings/ocr/languages")
                .should()
                .respond(200);
    }

    @Test
    public void test_list_ocr_languages_tesseract_not_installed() throws TikaConfigException {
        TesseractOCRParser mock = mock(TesseractOCRParser.class);
        when(mock.hasTesseract()).thenReturn(false);
        PropertiesProvider properties = new PropertiesProvider();
        configure(routes -> routes.add(new SettingsResource(properties, mock)));

        get("/api/settings/ocr/languages")
                .should()
                .respond(503);
    }

    @Test
    public void test_list_ocr_languages_throws_tika_config_exception() throws TikaConfigException {
        TesseractOCRParser mock = mock(TesseractOCRParser.class);
        when(mock.hasTesseract()).thenThrow(new TikaConfigException("tesseractPath doesn't point to an existing directory"));
        PropertiesProvider properties = new PropertiesProvider();
        configure(routes -> routes.add(new SettingsResource(properties, mock)));

        get("/api/settings/ocr/languages")
                .should()
                .respond(500);
    }

    @Test
    public void test_list_text_languages() {
        PropertiesProvider properties = new PropertiesProvider();
        configure(routes -> routes.add(new SettingsResource(properties, new TesseractOCRParser())));

        get("/api/settings/text/languages")
                .should()
                .respond(200)
                .contain("FRENCH")
                .contain("fra")
                .contain("VIETNAMESE")
                .contain("vie");
    }
}
