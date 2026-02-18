package org.icij.datashare.session;


import net.codestory.http.WebServer;
import net.codestory.http.filters.Filter;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import net.codestory.rest.Response;
import org.icij.datashare.PropertiesProvider;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;
import static org.fest.assertions.Assertions.assertThat;

public class OAuth2CookieFilterTest implements FluentRestTest {
    private static final WebServer identityProvider = new WebServer() {
        @Override
        protected Env createEnv() { return Env.prod();}
    }.startOnRandomPort();
    static PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
        put("messageBusAddress", "redis");
        put("oauthTokenUrl", "http://localhost:" + identityProvider.port() + "/oauth/token");
        put("oauthAuthorizeUrl", "http://localhost:" + identityProvider.port() + "/oauth/authorize");
        put("oauthApiUrl", "http://localhost:" + identityProvider.port() + "/api/v1/me.json");
        put("oauthSigninPath", "/auth/signin");
        put("oauthClientId", "12345");
        put("oauthClientSecret", "abcdef");
        put("oauthCallbackPath", "/auth/callback");
    }});
    static OAuth2CookieFilter oAuth2Filter = new OAuth2CookieFilter(propertiesProvider,
            new UsersInRedis(propertiesProvider), new RedisSessionIdStore(propertiesProvider));

    @Test(expected = IllegalStateException.class)
    public void test_callback_url_should_not_start_with_login_url() {
        oAuth2Filter = new OAuth2CookieFilter(new PropertiesProvider(new HashMap<>() {{
            put("oauthSigninPath", "/auth/login/");
            put("oauthCallbackPath", "/auth/login/callback");
        }}), new UsersInRedis(propertiesProvider), new RedisSessionIdStore(propertiesProvider));
    }

    @Test
    public void test_redirect_to_authorization_server() {
        this.get("/auth/signin")
                .should().contain("OAuth Authorize")
                .should().contain("client=12345")
                .should().contain("redirect_uri=http://localhost:" + datashare.port() + "/auth/callback")
                .should().contain("response_type=code")
                .should().contain("state=");
    }

    @Test
    public void test_redirect_to_authorization_server_with_x_forwarded_host() {
        this.get("/auth/signin").withHeader("x-forwarded-host", "localhost:9009")
                .should().contain("OAuth Authorize")
                .should().contain("client=12345")
                .should().contain("redirect_uri=http://localhost:9009/auth/callback")
                .should().contain("response_type=code")
                .should().contain("state=");
    }

    @Test
    public void test_callback_should_return_bad_request_with_bad_args() {
        this.get("/auth/callback").should().respond(400);
        this.get("/auth/callback?code=1234").should().respond(400);
        this.post("/auth/callback?code=1234&state=123").should().respond(400);
    }

    @Test
    public void test_callback_should_return_bad_request_when_state_is_wrong() {
        this.get("/auth/signin").should().respond(200);
        this.get("/auth/callback?code=1234&state=unknown").should().respond(400);
    }

    @Test
    public void test_get_post_should_return_unauthorized() {
        this.get("/protected").should().respond(401);
        this.post("/protected").should().respond(401);
    }

    @Test
    public void test_matches_api_paths_with_static_file_extensions() {
        assertThat(oAuth2Filter.matches("/api/users/me.css", null)).isTrue();
        assertThat(oAuth2Filter.matches("/api/users/me.js", null)).isTrue();
        assertThat(oAuth2Filter.matches("/api/users/me.png", null)).isTrue();
        assertThat(oAuth2Filter.matches("/api/users/me.ico", null)).isTrue();
        assertThat(oAuth2Filter.matches("/api/key/victim.woff", null)).isTrue();
        assertThat(oAuth2Filter.matches("/api/batch/search.map", null)).isTrue();
    }

    @Test
    public void test_matches_auth_paths_with_static_file_extensions() {
        assertThat(oAuth2Filter.matches("/auth/signin", null)).isTrue();
        assertThat(oAuth2Filter.matches("/auth/callback.css", null)).isTrue();
    }

    @Test
    public void test_api_path_with_extension_should_return_unauthorized() {
        this.get("/api/users/me.css").should().respond(401);
        this.get("/api/users/me.js").should().respond(401);
        this.get("/api/users/me.png").should().respond(401);
    }

    @Test
    public void test_state_token_cannot_be_used_as_session() throws Exception {
        // Grab the state from /auth/signin
        Response signinResponse = this.get("/auth/signin").response();
        String state = getState(signinResponse.content());
        assertThat(state).isNotEmpty();

        // Try to use the state as a session cookie (the reported attack)
        String fakeCookie = "{\"sessionId\":\"" + state + "\"}";
        this.get("/api/users/me").withHeader("Cookie", "_ds_session_id=" + fakeCookie)
                .should().respond(401);
    }

    @Test
    public void test_if_user_already_in_context_do_nothing() {
        datashare.configure(routes -> routes
                        .get("/protected", context -> format("hello %s uid=%s", context.currentUser().name(), context.currentUser().login()))
                        .filter((Filter) (uri, context, nextFilter) -> {
                            context.setCurrentUser(new DatashareUser("foo"));
                            return nextFilter.get();
                        }).filter(oAuth2Filter));

        this.get("/protected").should().respond(200).contain("foo");
    }

    @Test
    public void test_callback_should_call_api_with_code_and_state() throws Exception {
        Response response = this.get("/auth/signin").response();
        response = this.get("/auth/callback?code=a0b1c2d3e4f5&state=" + getState(response.content())).withFollowRedirect(false).response();
        String cookie = response.header("set-cookie").get(0);
        String[] cookieAll = cookie.split("=", 2);
        String cookieValue  = cookieAll[1].split(";")[0];
        this.get(response.header("location").get(0)).withHeader("cookie",cookieAll[0]+"="+cookieValue)
                .should().contain("hello Nobody")
                .should().contain("uid=123");
    }

    @BeforeClass
    public static void setUpClass() {
        identityProvider.configure(routes -> routes
                .get("/api/v1/me.json", new HashMap<String, String>() {{
                    put("uid", "123");
                    put("country", null);
                    put("name", "Nobody");
                    put("username", "nobody");
                    put("email", "no@bo.dy");
                }})
                .post("/oauth/token", (context -> new HashMap<String, String>() {{
                    put("access_token", "access_token_value");
                }}))
                .get("/oauth/authorize?client_id=:client_id&redirect_uri=:redirect_uri&response_type=:response_type&state=:state",
                        (c, client_id, redirect_uri, response_type, state) -> format(
                                "OAuth Authorize for client=%s redirect_uri=%s response_type=%s state=%s",
                                client_id, redirect_uri, response_type, state)));
    }

    @Before
    public void setUp() {
        datashare.configure(routes -> routes
                        .get("/", context -> format("hello %s uid=%s", context.currentUser().name(), context.currentUser().login()))
                        .get("/api/users/me", context -> format("hello %s", context.currentUser().login()))
                        .get("/protected", context -> "protected content")
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

    private static final WebServer datashare = new WebServer() {
        @Override
        protected Env createEnv() { return Env.prod();}
    }.startOnRandomPort();
}
