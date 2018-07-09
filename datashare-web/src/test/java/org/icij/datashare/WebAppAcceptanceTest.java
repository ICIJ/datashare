package org.icij.datashare;

import com.google.inject.Guice;
import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;

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
        server.configure(WebApp.getConfiguration(Guice.createInjector(new ProdServiceModule(new HashMap<String, String>() {{
            put("dataDir", WebAppAcceptanceTest.class.getResource("/data").getPath());
        }}))));
    }

    @Test
    public void test_root_serve_app() {
        get("/").should().contain("<title>datashare-client</title>");
    }

    @Test
    public void test_get_config() {
        get("/config").should().contain("\"clusterName\":\"datashare\"");
    }

    @Test
    public void test_get_file() {
        get("/data/downloadDoc.txt").should().respond(200).
                haveHeader("Content-Type", "text/plain;charset=UTF-8").contain("content of downloadDoc");
    }
}
