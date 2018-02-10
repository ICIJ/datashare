package org.icij.datashare;

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
        server.configure(WebApp.getConfiguration(new ProdServiceModule(null)));
    }

    @Test
    public void test_root_display_datashare_info() {
            get("/").should().contain("Datashare REST API");
        }
}
