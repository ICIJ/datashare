package org.icij.datashare;

import com.google.inject.Guice;
import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import org.junit.BeforeClass;
import org.junit.Test;

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
        server.configure(WebApp.getConfiguration(Guice.createInjector(new ProdServiceModule(null))));
    }

    @Test
    public void test_root_serve_app() {
        get("/").should().contain("<title>datashare-client</title>");
    }

    @Test
    public void test_get_config() throws Exception {
        get("/config").should().contain("\"clusterName\":\"datashare\"");
    }
}
