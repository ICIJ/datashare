package org.icij.datashare.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.http.security.SessionIdStore;
import net.codestory.rest.FluentRestTest;
import net.codestory.rest.Response;
import net.codestory.rest.RestAssert;
import org.icij.datashare.PropertiesProvider;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static java.lang.String.format;
import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class FormAuthFilterTest implements FluentRestTest {
    private static final WebServer server = new WebServer() {
        @Override
        protected Env createEnv() { return Env.prod(); }
    }.startOnRandomPort();

    private final UsersWritable users = mock(UsersWritable.class);
    private PostLoginEnroller enroller = mock(PostLoginEnroller.class);
    private FormAuthFilter filter;

    @Before
    public void setUp() {
        reset(users, enroller);
        filter = new FormAuthFilter(new PropertiesProvider(), users, SessionIdStore.inMemory(), enroller);
        server.configure(routes -> routes
                .get("/", context -> "home")
                .get("/api/users/me", context -> format("hello %s", context.currentUser().login()))
                .post("/api/data", context -> "created")
                .filter(filter));
    }

    @Test
    public void test_unauthenticated_api_returns_401() {
        this.get("/api/users/me").should().respond(401);
    }

    @Test
    public void test_unauthenticated_post_returns_401() {
        this.post("/api/data").should().respond(401);
    }

    @Test
    public void test_root_is_accessible_without_auth() {
        this.get("/").should().respond(200).should().contain("home");
    }

    @Test
    public void test_login_with_valid_credentials() {
        DatashareUser user = new DatashareUser(new HashMap<>() {{
            put("uid", "alice");
        }});
        when(users.find("alice", "secret")).thenReturn(user);
        when(users.find("alice")).thenReturn(user);

        Response response = postLogin("alice", "secret").response();

        assertThat(response.code()).isEqualTo(200);
    }

    @Test
    public void test_login_with_invalid_credentials_returns_401() {
        when(users.find("alice", "wrong")).thenReturn(null);

        postLogin("alice", "wrong").should().respond(401);
    }

    @Test
    public void test_login_with_missing_username_returns_401() {
        postLoginRaw(Map.of("password", "secret")).should().respond(401);
    }

    @Test
    public void test_login_with_missing_password_returns_401() {
        postLoginRaw(Map.of("username", "alice")).should().respond(401);
    }

    @Test
    public void test_login_get_passes_through() {
        // GET /auth/login is not handled by the filter (only POST is)
        // so it falls through to the next filter/route
        server.configure(routes -> routes
                .get("/auth/login", context -> "login form")
                .filter(filter));
        this.get("/auth/login").should().respond(200).should().contain("login form");
    }

    @Test
    public void test_session_cookie_grants_access() {
        DatashareUser user = new DatashareUser(new HashMap<>() {{
            put("uid", "bob");
        }});
        when(users.find("bob", "pass")).thenReturn(user);
        when(users.find("bob")).thenReturn(user);

        Response loginResponse = postLogin("bob", "pass").response();
        String cookie = loginResponse.header("set-cookie").get(0);
        String cookiePair = cookie.split(";")[0];

        this.get("/api/users/me")
                .withHeader("Cookie", cookiePair)
                .should().respond(200)
                .should().contain("hello bob");
    }

    @Test
    public void test_signout_clears_session() {
        DatashareUser user = new DatashareUser(new HashMap<>() {{
            put("uid", "charlie");
        }});
        when(users.find("charlie", "pass")).thenReturn(user);
        when(users.find("charlie")).thenReturn(user);

        Response loginResponse = postLogin("charlie", "pass").response();
        String cookie = loginResponse.header("set-cookie").get(0);
        String cookiePair = cookie.split(";")[0];

        Response signoutResponse = this.get("/auth/signout")
                .withFollowRedirect(false)
                .withHeader("Cookie", cookiePair)
                .response();
        assertThat(signoutResponse.code()).isEqualTo(303);

        this.get("/api/users/me")
                .withHeader("Cookie", cookiePair)
                .should().respond(401);
    }

    @Test
    public void test_enroller_is_called_on_login() {
        DatashareUser user = new DatashareUser(new HashMap<>() {{
            put("uid", "alice");
        }});
        when(users.find("alice", "secret")).thenReturn(user);

        postLogin("alice", "secret").should().respond(200);

        verify(enroller).enroll(any(DatashareUser.class));
    }

    @Test
    public void test_enroller_not_called_on_failed_login() {
        when(users.find("alice", "wrong")).thenReturn(null);

        postLogin("alice", "wrong").should().respond(401);

        verify(enroller, never()).enroll(any());
    }

    @Test
    public void test_enroller_null_does_not_throw() {
        FormAuthFilter filterWithoutEnroller = new FormAuthFilter(
                new PropertiesProvider(), users, SessionIdStore.inMemory(), null);
        server.configure(routes -> routes
                .get("/api/users/me", context -> format("hello %s", context.currentUser().login()))
                .filter(filterWithoutEnroller));

        DatashareUser user = new DatashareUser(new HashMap<>() {{
            put("uid", "alice");
        }});
        when(users.find("alice", "secret")).thenReturn(user);
        when(users.find("alice")).thenReturn(user);

        postLogin("alice", "secret").should().respond(200);
    }

    @Test
    public void test_login_with_invalid_json_returns_401() {
        this.postRaw("/auth/login", "application/json", "not json").should().respond(401);
    }

    @Test
    public void test_login_with_empty_body_returns_401() {
        this.postRaw("/auth/login", "application/json", "").should().respond(401);
    }

    @Test
    public void test_signout_post_is_not_handled() {
        this.post("/auth/signout").should().respond(404);
    }

    @Test
    public void test_login_with_blank_username_returns_401() {
        postLogin("", "secret").should().respond(401);
    }

    @Test
    public void test_login_with_blank_password_returns_401() {
        postLogin("alice", "").should().respond(401);
    }

    @Test
    public void test_matches_api_paths_with_static_file_extensions() {
        assertThat(filter.matches("/api/users/me.css", null)).isTrue();
        assertThat(filter.matches("/api/users/me.js", null)).isTrue();
        assertThat(filter.matches("/api/users/me.png", null)).isTrue();
    }

    @Test
    public void test_matches_auth_paths() {
        assertThat(filter.matches("/auth/login", null)).isTrue();
        assertThat(filter.matches("/auth/signout", null)).isTrue();
    }

    private RestAssert postLogin(String username, String password) {
        return postLoginRaw(Map.of("username", username, "password", password));
    }

    private RestAssert postLoginRaw(Map<String, String> body) {
        try {
            return this.postRaw("/auth/login", "application/json", new ObjectMapper().writeValueAsString(body));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int port() { return server.port(); }
}
