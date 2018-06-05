package org.icij.datashare.session;


import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import org.icij.datashare.PropertiesProvider;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;

import static java.lang.String.format;

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
        put("oauthClientId", "12345");
        put("oauthCallbackPath", "/auth/callback");
    }});
    static OAuth2CookieAuthFilter oAuth2Filter = new OAuth2CookieAuthFilter(propertiesProvider,
            new RedisUsers(propertiesProvider), new RedisSessionIdStore(propertiesProvider)) {
        @Override protected String createState() { return "mocks_state";}
    };

    @Test
    public void test_redirect_to_authorization_server() {
        this.get("/auth/login").should().contain("OAuth Authorize");
        this.get("/auth/login").should().contain("client=12345");
        this.get("/auth/login").should().contain("redirect_uri=http://localhost:" + datashare.port() + "/auth/callback");
        this.get("/auth/login").should().contain("response_type=code");
        this.get("/auth/login").should().contain("state=mocks_state");
    }

    @BeforeClass
    public static void setUpClass() {
        xemx.configure(routes -> routes.get("/api/v1/me.json", "{}")
                .get("/oauth/authorize?client_id=:client_id&redirect_uri=:redirect_uri&response_type=:response_type&state=:state",
                        (c, client_id, redirect_uri, response_type, state) -> format(
                                "OAuth Authorize for client=%s redirect_uri=%s response_type=%s state=%s",
                                client_id, redirect_uri, response_type, state)));
        datashare.configure(routes -> routes.get("/protected/url", "OK").filter(oAuth2Filter));
    }

    @Override
    public int port() { return datashare.port();}

    private static WebServer datashare = new WebServer() {
        @Override
        protected Env createEnv() { return Env.prod();}
    }.startOnRandomPort();
}