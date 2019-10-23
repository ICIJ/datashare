package org.icij.datashare.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import net.codestory.rest.Response;
import org.icij.datashare.mode.LocalMode;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static java.lang.String.format;
import static org.fest.assertions.Assertions.assertThat;

public class WebAppAcceptanceTest implements FluentRestTest {
    private static WebServer server = new WebServer() {
        @Override
        protected Env createEnv() {
            return Env.prod();
        }
    }.startOnRandomPort();

    @BeforeClass
    public static void setUpClass() {
        server.configure(new LocalMode(new HashMap<String, String>() {{
            put("dataDir", WebAppAcceptanceTest.class.getResource("/data").getPath());
        }}).createWebConfiguration());
    }

    @Before
    public void setUp() throws Exception {
        waitForDatashare();
    }

    @Test
    public void test_root_serve_app() {
        get("/").should().haveType("text/html").contain("<title>datashare-client</title>");
    }

    @Test
    public void test_get_config() {
        get("/config").should().haveType("application/json").
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

    private void waitForDatashare() throws Exception {
        for(int nbTries = 10; nbTries > 0 ; nbTries--) {
            if (get("/config").response().contentType().contains("application/json")) {
                return;
            }
            Thread.sleep(500); // ms
        }
        throw new TimeoutException("Connection to Datashare failed (maybe linked to Elasticsearch)");
    }

    @Override public int port() {
         return server.port();
     }
}
