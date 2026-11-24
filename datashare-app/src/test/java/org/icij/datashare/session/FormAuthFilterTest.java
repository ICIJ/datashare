package org.icij.datashare.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.http.security.SessionIdStore;
import net.codestory.http.security.Users;
import net.codestory.rest.FluentRestTest;
import net.codestory.rest.Response;
import net.codestory.rest.RestAssert;
import org.icij.datashare.PropertiesProvider;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class FormAuthFilterTest implements FluentRestTest {
    private static final String EMOJI = new String(Character.toChars(0x1F600));
    // the JSON escape of emoji, written without a unicode escape that javac would translate before compiling
    private static final String ESCAPED_EMOJI = "\\" + "uD83D\\" + "uDE00";
    private static final WebServer server = new WebServer() {
        @Override
        protected Env createEnv() { return Env.prod(); }
    }.startOnRandomPort();

    private final Users users = mock(Users.class);
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
        this.get("/api/users/me").should().respond(401).contain("authentication required");
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

        postLogin("alice", "wrong").should().respond(401).contain("authentication required");
    }

    @Test
    public void test_a_failed_login_does_not_reveal_whether_the_user_exists() {
        // alice exists, ghost does not, and neither password is right. Without this stub both
        // lookups return null and take the same branch, so the test could never catch an oracle
        // introduced by an existence pre-check.
        when(users.find("alice")).thenReturn(new DatashareUser("alice"));
        when(users.find("alice", "x")).thenReturn(null);
        when(users.find("ghost")).thenReturn(null);
        when(users.find("ghost", "x")).thenReturn(null);

        String unknownUser = postLogin("ghost", "x").response().content();
        String wrongPassword = postLogin("alice", "x").response().content();

        assertThat(unknownUser).isEqualTo(wrongPassword);
    }

    @Test
    public void test_the_unauthenticated_api_chain_includes_the_api_key_filter() {
        // ApiKeyFilter sits in front of this filter for every /api URI in server mode, so a test
        // wiring only FormAuthFilter asserts a chain no deployment runs (see #2444).
        server.configure(routes -> routes
                .get("/api/users/me", context -> "never reached")
                .filter(new ApiKeyFilter(users, apiKey -> null, null))
                .filter(filter));

        this.get("/api/users/me").should().respond(401).contain("authentication required");
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
    public void test_login_with_a_body_of_empty_arrays_above_the_limit_returns_413() {
        this.postRaw("/auth/login", "application/json", "[" + "[],".repeat(32 * 1024) + "[]]")
                .should().respond(413);
    }

    @Test
    public void test_login_with_an_oversized_password_returns_413() {
        postLoginRaw(Map.of("username", "alice", "password", "p".repeat(64 * 1024))).should().respond(413);
    }

    @Test
    public void test_login_with_an_oversized_body_does_not_look_up_the_user() {
        Response response = postLoginRaw(Map.of("username", "alice", "password", "p".repeat(64 * 1024))).response();
        assertThat(response.code()).isEqualTo(413);

        verify(users, never()).find(any(), any());
        verify(enroller, never()).enroll(any());
        assertThat(response.header("set-cookie")).isNullOrEmpty();

    }

    @Test
    public void test_login_still_works_after_an_oversized_body_was_rejected() {
        DatashareUser user = new DatashareUser(new HashMap<>() {{
            put("uid", "alice");
        }});
        when(users.find("alice", "secret")).thenReturn(user);
        postLoginRaw(Map.of("username", "alice", "password", "p".repeat(64 * 1024))).should().respond(413);

        postLogin("alice", "secret").should().respond(200);
    }

    // The limit is the longest possible login payload: 128 characters for the login and for the password, each one
    // an emoji on 12 bytes (😀), plus 29 bytes of JSON structure.
    @Test
    public void test_login_accepts_the_longest_credentials_of_exactly_the_limit() {
        String credential = EMOJI.repeat(128);
        DatashareUser user = new DatashareUser(new HashMap<>() {{
            put("uid", "alice");
        }});
        when(users.find(credential, credential)).thenReturn(user);
        String body = longestLoginBody();
        assertThat(body.getBytes(UTF_8).length).isEqualTo(2 * 128 * 12 + 29);

        this.postRaw("/auth/login", "application/json", body).should().respond(200);

        verify(users).find(credential, credential);
    }

    @Test
    public void test_login_rejects_a_body_one_byte_above_the_limit() {
        String body = longestLoginBody() + " ";
        assertThat(body.getBytes(UTF_8).length).isEqualTo(2 * 128 * 12 + 29 + 1);

        this.postRaw("/auth/login", "application/json", body).should().respond(413);

        verify(users, never()).find(any(), any());
    }

    private static String longestLoginBody() {
        String escaped = ESCAPED_EMOJI.repeat(128);
        return "{\"username\":\"" + escaped + "\",\"password\":\"" + escaped + "\"}";
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
