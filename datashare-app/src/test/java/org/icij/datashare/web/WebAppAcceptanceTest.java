package org.icij.datashare.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.codestory.rest.Response;
import org.icij.datashare.mode.LocalMode;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static java.lang.String.format;
import static org.fest.assertions.Assertions.assertThat;

public class WebAppAcceptanceTest extends AbstractProdWebServerTest {
    @Before
    public void setUp() throws Exception {
        configure(new LocalMode(new HashMap<String, String>() {{
            put("dataDir", WebAppAcceptanceTest.class.getResource("/data").getPath());
            put("extensionsDir", WebAppAcceptanceTest.class.getResource("/extensions").getPath());
            put("pluginsDir", WebAppAcceptanceTest.class.getResource("/plugins").getPath());
        }}).createWebConfiguration());
        waitForDatashare();
    }

    @Test
    public void test_root_serve_app() {
        get("/").should().haveType("text/html").contain("<title>datashare-client</title>");
    }

    @Test
    public void test_get_settings() {
        get("/settings").should().haveType("application/json").
                contain(format("\"dataDir\":\"%s\"", getClass().getResource("/data").getPath()));
    }

    @Test
    public void test_get_version() throws Exception {
        Response response = get("/version").response();

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.contentType()).contains("application/json");
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> map = mapper.readValue(response.content(), new TypeReference<Map<String, Object>>() {});
        assertThat(map.keySet()).contains("git.commit.id", "git.commit.id.abbrev");
    }

    @Test
    public void test_get_extensions_list_installed() throws Exception {
        get("/api/extensions").should().haveType("application/json").contain("\"installed\":true");
    }

    @Test
    public void test_get_plugins_list_installed() throws Exception {
        get("/api/plugins").should().haveType("application/json").contain("\"installed\":true");
    }

    @Test
    public void test_get_extensions_plugins_without_directory() throws Exception {
        configure(new LocalMode(new HashMap<String, String>() {{ }}).createWebConfiguration());
        waitForDatashare();
        get("/api/extensions").should().haveType("application/json").contain("\"installed\":false");
        get("/api/plugins").should().haveType("application/json").contain("\"installed\":false");
    }

    private void waitForDatashare() throws Exception {
        for(int nbTries = 10; nbTries > 0 ; nbTries--) {
            if (get("/settings").response().contentType().contains("application/json")) {
                return;
            }
            Thread.sleep(500); // ms
        }
        throw new TimeoutException("Connection to Datashare failed (maybe linked to Elasticsearch)");
    }
}
