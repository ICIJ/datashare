package org.icij.datashare;

import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import org.icij.datashare.session.RedisSessionManager;
import org.icij.datashare.session.SessionFilter;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;

public class WebAppWithAuthenticationTest implements FluentRestTest {
    private static WebServer server = new WebServer() {
        @Override
        protected Env createEnv() { return Env.prod();}
    }.startOnRandomPort();

    @Override
    public int port() { return server.port();}

    @BeforeClass
    public static void setUpClass() {
        server.configure(
                routes -> routes.get("/public/url", "OK").get("/protected/url", "OK")
                        .filter(new SessionFilter(new RedisSessionManager(new PropertiesProvider(new HashMap<String, String>() {{
                            put("messageBusAddress", "redis");
                        }})), "/protected"))
        );
    }

    @Test
    public void test_authenticated_user() throws Exception {

    }

    @Test
    public void test_unauthenticated_user() throws Exception {
        get("/public/url").should().contain("OK");
        get("/protected/url").should().respond(401);
    }
}