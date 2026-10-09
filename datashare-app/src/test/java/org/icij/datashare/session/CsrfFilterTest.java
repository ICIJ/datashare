package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.Cookie;
import net.codestory.http.Cookies;
import net.codestory.http.NewCookie;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.User;
import org.icij.datashare.PropertiesProvider;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CsrfFilterTest {
    private final Payload next = Payload.ok();
    private final PayloadSupplier nextFilter = () -> next;
    private final Context context = mock(Context.class);
    private CsrfFilter csrfFilter;

    @Before
    public void setUp() {
        csrfFilter = filterIn("SERVER");
        when(context.cookies()).thenReturn(mock(Cookies.class));
    }

    @Test
    public void test_matches_api_paths() {
        assertThat(csrfFilter.matches("/api/users", null)).isTrue();
        assertThat(csrfFilter.matches("/api/", null)).isTrue();
        assertThat(csrfFilter.matches("/auth/signin", null)).isFalse();
        assertThat(csrfFilter.matches("/other", null)).isFalse();
    }

    @Test
    public void test_get_requests_pass_through() throws Exception {
        when(context.method()).thenReturn("GET");
        Payload payload = csrfFilter.apply("/api/users", context, nextFilter);
        assertThat(payload.code()).isEqualTo(200);
    }

    @Test
    public void test_options_requests_pass_through() throws Exception {
        when(context.method()).thenReturn("OPTIONS");
        Payload payload = csrfFilter.apply("/api/users", context, nextFilter);
        assertThat(payload.code()).isEqualTo(200);
    }

    @Test
    public void test_head_requests_pass_through() throws Exception {
        when(context.method()).thenReturn("HEAD");
        Payload payload = csrfFilter.apply("/api/users", context, nextFilter);
        assertThat(payload.code()).isEqualTo(200);
    }

    @Test
    public void test_post_without_user_passes_through() throws Exception {
        when(context.method()).thenReturn("POST");
        Payload payload = csrfFilter.apply("/api/users", context, nextFilter);
        assertThat(payload).isSameAs(next);
    }

    @Test
    public void test_post_with_valid_csrf_token_passes_through() throws Exception {
        when(context.method()).thenReturn("POST");
        when(context.currentUser()).thenReturn(mock(User.class));
        String token = "abc123";
        when(context.cookies()).thenReturn(new SimpleCookies() {{
            put("_ds_csrf_token", new NewCookie("_ds_csrf_token", token));
        }});
        when(context.header("X-DS-CSRF-TOKEN")).thenReturn(token);

        Payload payload = csrfFilter.apply("/api/users", context, nextFilter);
        assertThat(payload).isSameAs(next);
    }

    @Test
    public void test_post_with_missing_csrf_header_returns_403() throws Exception {
        when(context.method()).thenReturn("POST");
        when(context.currentUser()).thenReturn(mock(User.class));
        when(context.cookies()).thenReturn(new SimpleCookies() {{
            put("_ds_csrf_token", new NewCookie("_ds_csrf_token", "abc123"));
        }});
        // No X-DS-CSRF-TOKEN header

        Payload payload = csrfFilter.apply("/api/users", context, nextFilter);
        assertThat(payload.code()).isEqualTo(403);
        assertThat(payload.rawContentType()).isEqualTo("application/json");
        assertThat((String) payload.rawContent()).contains("CSRF token wrong or missing");
    }

    @Test
    public void test_post_with_mismatched_csrf_token_returns_403() throws Exception {
        when(context.method()).thenReturn("POST");
        when(context.currentUser()).thenReturn(mock(User.class));
        when(context.cookies()).thenReturn(new SimpleCookies() {{
            put("_ds_csrf_token", new NewCookie("_ds_csrf_token", "cookie_token"));
        }});
        when(context.header("X-DS-CSRF-TOKEN")).thenReturn("different_token");

        Payload payload = csrfFilter.apply("/api/users", context, nextFilter);
        assertThat(payload.code()).isEqualTo(403);
    }

    @Test
    public void test_put_delete_patch_require_csrf() throws Exception {
        for (String method : new String[]{"PUT", "DELETE", "PATCH"}) {
            when(context.method()).thenReturn(method);
            when(context.currentUser()).thenReturn(mock(User.class));
            when(context.cookies()).thenReturn(new SimpleCookies());

            Payload payload = csrfFilter.apply("/api/users", context, nextFilter);
            assertThat(payload.code()).as("Expected 403 for method " + method).isEqualTo(403);
        }
    }

    @Test
    public void test_get_sets_csrf_cookie_when_user_authenticated_without_csrf() throws Exception {
        when(context.method()).thenReturn("GET");
        when(context.currentUser()).thenReturn(mock(User.class));
        when(context.cookies()).thenReturn(new SimpleCookies());

        Payload payload = csrfFilter.apply("/api/users", context, nextFilter);
        assertThat(payload.code()).isEqualTo(200);
        assertThat(payload.cookies()).isNotEmpty();
        Cookie csrfCookie = payload.cookies().stream()
                .filter(c -> c.name().equals("_ds_csrf_token"))
                .findFirst().orElse(null);
        assertThat(csrfCookie).isNotNull();
        assertThat(csrfCookie.value()).isNotEmpty();
        assertThat(csrfCookie.path()).isEqualTo("/");
        assertThat(csrfCookie.isHttpOnly()).isFalse();
    }

    @Test
    public void test_get_does_not_set_csrf_cookie_when_no_user() throws Exception {
        when(context.method()).thenReturn("GET");
        Payload payload = csrfFilter.apply("/api/users", context, nextFilter);
        assertThat(payload.code()).isEqualTo(200);
        assertThat(payload.cookies()).isEmpty();
    }

    @Test
    public void test_get_does_not_set_csrf_cookie_when_csrf_already_exists() throws Exception {
        when(context.method()).thenReturn("GET");
        when(context.currentUser()).thenReturn(mock(User.class));
        when(context.cookies()).thenReturn(new SimpleCookies() {{
            put("_ds_csrf_token", new NewCookie("_ds_csrf_token", "existing_token"));
        }});

        Payload payload = csrfFilter.apply("/api/users", context, nextFilter);
        assertThat(payload.code()).isEqualTo(200);
        assertThat(payload.cookies()).isEmpty();
    }

    @Test
    public void test_get_does_not_set_csrf_cookie_on_non_2xx_response() throws Exception {
        when(context.method()).thenReturn("GET");
        when(context.currentUser()).thenReturn(mock(User.class));
        when(context.cookies()).thenReturn(new SimpleCookies());

        for (int status : new int[]{301, 401, 403, 500}) {
            Payload payload = csrfFilter.apply("/api/users", context, () -> new Payload(status));
            assertThat(payload.code()).isEqualTo(status);
            assertThat(payload.cookies()).as("Expected no CSRF cookie for status " + status).isEmpty();
        }
    }

    @Test
    public void test_post_with_user_but_no_csrf_cookie_returns_403() throws Exception {
        when(context.method()).thenReturn("POST");
        when(context.currentUser()).thenReturn(mock(User.class));
        when(context.cookies()).thenReturn(new SimpleCookies());
        when(context.header("X-DS-CSRF-TOKEN")).thenReturn("some_token");

        Payload payload = csrfFilter.apply("/api/users", context, nextFilter);
        assertThat(payload.code()).isEqualTo(403);
    }

    @Test
    public void test_post_authenticated_by_api_key_without_session_cookie_passes_through() throws Exception {
        when(context.method()).thenReturn("POST");
        when(context.currentUser()).thenReturn(mock(User.class));
        ApiKeyFilter.markAuthenticated(context);

        Payload payload = csrfFilter.apply("/api/mcp", context, nextFilter);
        assertThat(payload).isSameAs(next);
    }

    @Test
    public void test_post_with_bearer_header_but_not_authenticated_by_api_key_filter_needs_csrf_token() throws Exception {
        when(context.method()).thenReturn("POST");
        when(context.currentUser()).thenReturn(mock(User.class));
        when(context.header("authorization")).thenReturn("Bearer some-key");

        Payload payload = csrfFilter.apply("/api/mcp", context, nextFilter);
        assertThat(payload.code()).isEqualTo(403);
    }

    @Test
    public void test_post_with_bearer_and_session_cookie_still_needs_csrf_token() throws Exception {
        when(context.method()).thenReturn("POST");
        when(context.currentUser()).thenReturn(mock(User.class));
        when(context.header("authorization")).thenReturn("Bearer some-key");
        when(context.cookies()).thenReturn(new SimpleCookies() {{
            put("_ds_session_id", new NewCookie("_ds_session_id", "session"));
        }});

        Payload payload = csrfFilter.apply("/api/mcp", context, nextFilter);
        assertThat(payload.code()).isEqualTo(403);
    }

    private static CsrfFilter filterIn(String mode) {
        return new CsrfFilter(new PropertiesProvider(Map.<String, Object>of("mode", mode)));
    }

    private Payload postMcp(CsrfFilter filter, String uri, String contentType, String host) throws Exception {
        when(context.method()).thenReturn("POST");
        when(context.currentUser()).thenReturn(mock(User.class));
        when(context.header("Content-Type")).thenReturn(contentType);
        when(context.header("Host")).thenReturn(host);
        return filter.apply(uri, context, nextFilter);
    }

    @Test
    public void test_local_mode_mcp_json_from_loopback_host_passes_without_csrf_token() throws Exception {
        for (String mode : new String[]{"LOCAL", "EMBEDDED"}) {
            for (String host : new String[]{"localhost:8080", "127.0.0.1", "[::1]:8080", "LocalHost:8080"}) {
                assertThat(postMcp(filterIn(mode), "/api/mcp", "application/json", host)).as(mode + " " + host).isSameAs(next);
            }
        }
        assertThat(postMcp(filterIn("LOCAL"), "/api/mcp?x=1", "Application/JSON; charset=utf-8", "localhost:8080")).isSameAs(next);
    }

    @Test
    public void test_local_mode_mcp_rejects_foreign_host_wrong_content_type_or_other_path() throws Exception {
        CsrfFilter local = filterIn("LOCAL");
        assertThat(postMcp(local, "/api/mcp", "application/json", "evil.com:8080").code()).isEqualTo(403);
        assertThat(postMcp(local, "/api/mcp", "application/json", "localhost.evil.com").code()).isEqualTo(403);
        assertThat(postMcp(local, "/api/mcp", "application/json", null).code()).isEqualTo(403);
        assertThat(postMcp(local, "/api/mcp", "text/plain", "localhost:8080").code()).isEqualTo(403);
        assertThat(postMcp(local, "/api/mcp", null, "localhost:8080").code()).isEqualTo(403);
        assertThat(postMcp(local, "/api/project", "application/json", "localhost:8080").code()).isEqualTo(403);
        assertThat(postMcp(local, "/api/mcp/x", "application/json", "localhost:8080").code()).isEqualTo(403);
    }

    @Test
    public void test_server_or_unset_mode_mcp_still_needs_csrf_token() throws Exception {
        assertThat(postMcp(filterIn("SERVER"), "/api/mcp", "application/json", "localhost:8080").code()).isEqualTo(403);
        assertThat(postMcp(new CsrfFilter(new PropertiesProvider(Map.<String, Object>of())), "/api/mcp", "application/json", "localhost:8080").code()).isEqualTo(403);
    }
}
