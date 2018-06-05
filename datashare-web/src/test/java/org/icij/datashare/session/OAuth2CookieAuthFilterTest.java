package org.icij.datashare.session;


import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import org.icij.datashare.PropertiesProvider;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;

public class OAuth2CookieAuthFilterTest implements FluentRestTest {
    private static WebServer xemx = new WebServer() {
        @Override
        protected Env createEnv() { return Env.prod();}
    }.startOnRandomPort();
    static PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<String, String>() {{
        put("messageBusAddress", "redis");
        put("oauthRedirectUrl", "http://localhost:" + xemx.port() + "/oauth/authorize");
        put("protectedUriPrefix", "/protected");
        put("oauthLoginPath", "/auth/login");
        put("oauthCallbackPath", "/auth/callback");
    }});
    static OAuth2CookieAuthFilter oAuth2Filter = new OAuth2CookieAuthFilter(propertiesProvider, new RedisUsers(propertiesProvider), new RedisSessionIdStore(propertiesProvider));

    @Test
    public void test_redirect_to_authorization_server() {
        this.get("/auth/login").should().contain("OAuth Authorize");
    }

    @BeforeClass
    public static void setUpClass() {
        xemx.configure(routes -> routes.get("/api/v1/me.json", "{}").get("/oauth/authorize", "OAuth Authorize"));
        datashare.configure(routes -> routes.get("/protected/url", "OK").filter(oAuth2Filter));
    }

    @Override
    public int port() { return datashare.port();}

    private static WebServer datashare = new WebServer() {
        @Override
        protected Env createEnv() { return Env.prod();}
    }.startOnRandomPort();
}