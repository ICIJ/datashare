package org.icij.datashare.session;


import net.codestory.http.Context;
import net.codestory.http.Cookie;
import net.codestory.http.WebServer;
import net.codestory.http.filters.Filter;
import net.codestory.http.filters.auth.AuthData;
import net.codestory.http.misc.Env;
import net.codestory.http.payload.Payload;
import net.codestory.rest.FluentRestTest;
import net.codestory.http.security.SessionIdStore;
import net.codestory.rest.Response;
import net.codestory.rest.RestAssert;
import org.icij.datashare.PropertiesProvider;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.session.OAuth2CookieFilter.STATE_COOKIE_PREFIX;
import static org.icij.datashare.session.OAuth2CookieFilter.stateCookieName;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
    static UserStore userStore = mock(UserStore.class);
    static OAuth2CookieFilter oAuth2Filter = new OAuth2CookieFilter(propertiesProvider,
            new UsersIdProviderRedisCache(propertiesProvider), userStore, new RedisSessionIdStore(propertiesProvider), null);

    @Test(expected = IllegalStateException.class)
    public void test_callback_url_should_not_start_with_login_url() {
        oAuth2Filter = new OAuth2CookieFilter(new PropertiesProvider(new HashMap<>() {{
            put("oauthSigninPath", "/auth/login/");
            put("oauthCallbackPath", "/auth/login/callback");
        }}), new UsersIdProviderRedisCache(propertiesProvider), userStore, new RedisSessionIdStore(propertiesProvider), null);
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
    public void test_redirect_uri_uses_the_first_entry_of_a_proxy_chain() {
        this.get("/auth/signin").withHeader("x-forwarded-host", "localhost:9009")
                .withHeader("x-forwarded-proto", "https, http")
                .should().contain("redirect_uri=https://localhost:9009/auth/callback");
    }

    @Test
    public void test_signin_does_not_touch_the_session_store() {
        SessionIdStore sessionIdStore = mock(SessionIdStore.class);
        OAuth2CookieFilter filter = new OAuth2CookieFilter(propertiesProvider,
                new UsersIdProviderRedisCache(propertiesProvider), userStore, sessionIdStore, null);
        Context context = mock(Context.class, RETURNS_DEEP_STUBS);

        filter.signin(context);

        verifyNoInteractions(sessionIdStore);
    }

    @Test
    public void test_state_cookie_is_secure_when_the_option_is_set() {
        PropertiesProvider secureProvider = new PropertiesProvider(Map.of("secureSessionCookie", "true"));
        OAuth2CookieFilter filter = new OAuth2CookieFilter(secureProvider,
                new UsersIdProviderRedisCache(propertiesProvider), userStore, mock(SessionIdStore.class), null);

        Payload payload = filter.signin(mock(Context.class, RETURNS_DEEP_STUBS));

        assertThat(stateCookieIn(payload).isSecure()).isTrue();
    }

    @Test
    public void test_state_cookie_is_secure_behind_an_https_proxy() {
        Context context = mock(Context.class, RETURNS_DEEP_STUBS);
        when(context.header("x-forwarded-proto")).thenReturn("https");

        Payload payload = oAuth2Filter.signin(context);

        assertThat(stateCookieIn(payload).isSecure()).isTrue();
    }

    @Test
    public void test_state_cookie_is_not_secure_over_plain_http() {
        Payload payload = oAuth2Filter.signin(mock(Context.class, RETURNS_DEEP_STUBS));

        assertThat(stateCookieIn(payload).isSecure()).isFalse();
    }

    @Test
    public void test_signin_sets_the_state_in_a_cookie() {
        Response signinResponse = signinWithoutRedirect();

        assertThat(stateCookieOf(signinResponse)).isEqualTo(stateOf(signinResponse));
    }

    @Test
    public void test_callback_rejects_state_without_its_cookie() {
        String state = stateOf(signinWithoutRedirect());

        assertRedirectedHome(this.get("/auth/callback?code=a0b1c2d3e4f5&state=" + state).withFollowRedirect(false));
    }

    @Test
    public void test_callback_rejects_state_from_another_signin() {
        String state = stateOf(signinWithoutRedirect());
        String otherState = stateOf(signinWithoutRedirect());

        assertRedirectedHome(callbackWithStateCookie(state, otherState));
    }

    @Test
    public void test_callback_rejects_tampered_state() {
        String state = stateOf(signinWithoutRedirect());
        String tamperedState = state.replaceFirst("\\.[0-9]+\\.", ".9999999999.");

        assertRedirectedHome(callbackWithStateCookie(tamperedState, tamperedState));
    }

    @Test
    public void test_two_pending_signins_in_one_browser_both_complete() {
        Response firstSignin = signinWithoutRedirect();
        Response secondSignin = signinWithoutRedirect();
        String browserCookies = setCookieOf(firstSignin) + "; " + setCookieOf(secondSignin);

        for (Response signin : new Response[] {firstSignin, secondSignin}) {
            String callback = "/auth/callback?code=a0b1c2d3e4f5&state=" + stateOf(signin);
            Response response = this.get(callback).withHeader("Cookie", browserCookies).withFollowRedirect(false)
                                    .response();

            assertThat(response.code()).isEqualTo(303);
            assertThat(response.header("set-cookie").toString()).contains("_ds_session_id=");
        }
    }

    @Test
    public void test_callback_clears_the_state_cookie() {
        String state = stateOf(signinWithoutRedirect());

        Response response = callbackWithStateCookie(state, state).response();

        assertThat(response.code()).isEqualTo(303);
        assertThat(response.header("set-cookie").toString()).contains(stateCookieName(state) + "=;");
    }

    @Test
    public void test_state_is_rejected_once_expired() {
        Instant signinTime = Instant.now().minus(Duration.ofMinutes(11));
        String state = oAuth2Filter.createState(signinTime);

        assertThat(oAuth2Filter.isValidState(state, state, Instant.now())).isFalse();
    }

    @Test
    public void test_state_is_accepted_before_it_expires() {
        Instant signinTime = Instant.now().minus(Duration.ofMinutes(9));
        String state = oAuth2Filter.createState(signinTime);

        assertThat(oAuth2Filter.isValidState(state, state, Instant.now())).isTrue();
    }

    @Test
    public void test_session_id_is_not_a_valid_state() {
        String sessionId = oAuth2Filter.newSessionId("foo");

        assertThat(oAuth2Filter.isValidState(sessionId, sessionId, Instant.now())).isFalse();
    }

    @Test
    public void test_callback_should_return_bad_request_with_bad_args() {
        this.get("/auth/callback").should().respond(400);
        this.get("/auth/callback?code=1234").should().respond(400);
        this.post("/auth/callback?code=1234&state=123").should().respond(400);
    }

    @Test
    public void test_callback_redirects_home_when_state_is_wrong() {
        this.get("/auth/signin").should().respond(200);
        assertRedirectedHome(callbackWithStateCookie("unknown", "unknown"));
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
    public void test_state_token_cannot_be_used_as_session() {
        AuthData stateAsSession = new AuthData();
        stateAsSession.sessionId = oAuth2Filter.createState(Instant.now());
        Context context = mock(Context.class, RETURNS_DEEP_STUBS);
        when(context.cookies().value(anyString(), eq(AuthData.class))).thenReturn(stateAsSession);

        assertThat(oAuth2Filter.readSessionIdInCookie(context)).isNull();
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
        String state = stateOf(signinWithoutRedirect());
        Response response = callbackWithStateCookie(state, state).response();
        String cookie = response.header("set-cookie").get(0);
        String[] cookieAll = cookie.split("=", 2);
        String cookieValue  = cookieAll[1].split(";")[0];
        this.get(response.header("location").get(0)).withHeader("cookie",cookieAll[0]+"="+cookieValue)
                .should().contain("hello Nobody")
                .should().contain("uid=123");
    }

    @Test
    public void test_callback_should_persist_user_in_user_store_at_each_login() throws Exception {
        reset(userStore);
        String firstState = stateOf(signinWithoutRedirect());
        callbackWithStateCookie(firstState, firstState).response();
        String secondState = stateOf(signinWithoutRedirect());
        callbackWithStateCookie(secondState, secondState).response();

        verify(userStore, times(2)).save(argThat(user -> "123".equals(user.id)));
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

    private Cookie stateCookieIn(Payload payload) {
        return payload.cookies().stream().filter(cookie -> cookie.name().startsWith(STATE_COOKIE_PREFIX)).findFirst()
                      .orElseThrow();
    }

    private Response signinWithoutRedirect() {
        return this.get("/auth/signin").withFollowRedirect(false).response();
    }

    private String stateOf(Response signinResponse) {
        String location = signinResponse.header("location").get(0);
        return getState(location);
    }

    private String stateCookieOf(Response signinResponse) {
        String cookiePrefix = stateCookieName(stateOf(signinResponse)) + "=";
        String setCookie = setCookieOf(signinResponse);
        return setCookie.substring(cookiePrefix.length());
    }

    private String setCookieOf(Response signinResponse) {
        String setCookie = signinResponse.header("set-cookie").get(0);
        return setCookie.split(";")[0];
    }

    private void assertRedirectedHome(RestAssert callback) {
        Response response = callback.response();
        assertThat(response.code()).isEqualTo(303);
        assertThat(response.header("location").get(0)).isEqualTo("/");
        assertThat(response.header("set-cookie")).isNull();
    }

    private RestAssert callbackWithStateCookie(String state, String cookieState) {
        String callback = "/auth/callback?code=a0b1c2d3e4f5&state=" + state;
        String cookie = stateCookieName(cookieState) + "=" + cookieState;
        return this.get(callback).withHeader("Cookie", cookie).withFollowRedirect(false);
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
