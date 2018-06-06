package org.icij.datashare.session;


import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import net.codestory.rest.Response;
import org.icij.datashare.PropertiesProvider;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;

public class OAuth2CookieAuthFilterTest implements FluentRestTest {
    private static WebServer xemx = new WebServer() {
        @Override
        protected Env createEnv() { return Env.prod();}
    }.startOnRandomPort();
    static PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<String, String>() {{
        put("messageBusAddress", "redis");
        put("oauthRedirectUrl", "http://localhost:" + xemx.port() + "/oauth/authorize");
        put("oauthApiUrl", "http://localhost:" + xemx.port() + "/api/v1/me.json");
        put("oauthLoginPath", "/auth/login");
        put("oauthClientId", "12345");
        put("oauthCallbackPath", "/auth/callback");
    }});
    static OAuth2CookieAuthFilter oAuth2Filter = new OAuth2CookieAuthFilter(propertiesProvider,
            new RedisUsers(propertiesProvider), new RedisSessionIdStore(propertiesProvider));

    @Test
    public void test_redirect_to_authorization_server() {
        this.get("/auth/login")
                .should().contain("OAuth Authorize")
                .should().contain("client=12345")
                .should().contain("redirect_uri=http://localhost:" + datashare.port() + "/auth/callback")
                .should().contain("response_type=code")
                .should().contain("state=");
    }

    @Test
    public void test_callback_should_return_bad_request_with_bad_args() throws Exception {
        this.get("/auth/callback").should().respond(400);
        this.get("/auth/callback?code=1234").should().respond(400);
        this.post("/auth/callback?code=1234&state=123").should().respond(400);
    }

    @Test
    public void test_callback_should_return_bad_request_when_state_is_wrong() throws Exception {
        this.get("/auth/login").should().respond(200);
        this.get("/auth/callback?code=1234&state=unknown").should().respond(400);
    }

    @Test
    public void test_callback_should_call_api_with_code_and_state() throws Exception {
        Response response = this.get("/auth/login").response();
        this.get("/auth/callback?code=a0b1c2d3e4f5&state=" + getState(response.content())).should().respond(200)
                .contain("hello Nobody")
                .contain("uid=123");
    }

    @BeforeClass
    public static void setUpClass() {
        xemx.configure(routes -> routes
                .get("/api/v1/me.json", new HashMap<String, String>() {{
                    put("uid", "123");
                    put("name", "Nobody");
                    put("email", "no@bo.dy");
                }})
                .get("/oauth/authorize?client_id=:client_id&redirect_uri=:redirect_uri&response_type=:response_type&state=:state",
                        (c, client_id, redirect_uri, response_type, state) -> format(
                                "OAuth Authorize for client=%s redirect_uri=%s response_type=%s state=%s",
                                client_id, redirect_uri, response_type, state)));
        datashare.configure(routes -> routes
                .get("/", context -> format("hello %s uid=%s", context.currentUser().name(), context.currentUser().login()))
                .filter(oAuth2Filter));
    }

    private String getState(String content) {
        Pattern p = Pattern.compile(".*state=(.*)");
        Matcher m = p.matcher(content);
        if (m.matches()) {
            return m.group(1);
        }
        return "";
    }

    @Override
    public int port() { return datashare.port();}

    private static WebServer datashare = new WebServer() {
        @Override
        protected Env createEnv() { return Env.prod();}
    }.startOnRandomPort();
}