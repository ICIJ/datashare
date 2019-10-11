package org.icij.datashare.web;


import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import org.junit.Before;
import org.junit.Test;

public class RootResourceTest implements FluentRestTest {
    private static WebServer server = new WebServer() {
        @Override
        protected Env createEnv() {
            return Env.prod();
        }
    }.startOnRandomPort();

    @Before
    public void setUp() {
        server.configure(routes -> routes.add(RootResource.class));
    }

    @Test
    public void test_get_public_config() {
        get("/config").should().respond(200);
    }

    @Test
    public void test_get_version() {
        get("/version").should().respond(200);
    }

    @Override
    public int port() { return server.port();}
}
