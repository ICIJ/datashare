package org.icij.datashare.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import net.codestory.rest.Response;
import org.icij.datashare.mode.LocalMode;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static java.lang.String.format;
import static org.fest.assertions.Assertions.assertThat;

public class WebAppAcceptanceTest implements FluentRestTest {
    private static WebServer server = new WebServer() {
        @Override
        protected Env createEnv() {
            return Env.prod();
        }
    }.startOnRandomPort();

    @Override
    public int port() {
        return server.port();
    }

    @BeforeClass
    public static void setUpClass() {
        server.configure(new LocalMode(new HashMap<String, String>() {{
            put("dataDir", WebAppAcceptanceTest.class.getResource("/data").getPath());
        }}).createWebConfiguration());
    }

    @Test
    public void test_root_serve_app() {
        get("/").should().contain("<title>datashare-client</title>");
    }

    @Test
    public void test_get_config() {
        get("/config").should().contain(format("\"dataDir\":\"%s\"", getClass().getResource("/data").getPath()));
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
}
