package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.Cookies;
import net.codestory.http.NewCookie;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import org.junit.Before;
import org.junit.Test;

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
        csrfFilter = new CsrfFilter();
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
        assertThat(payload).isSameAs(next);
    }

    @Test
    public void test_options_requests_pass_through() throws Exception {
        when(context.method()).thenReturn("OPTIONS");
        Payload payload = csrfFilter.apply("/api/users", context, nextFilter);
        assertThat(payload).isSameAs(next);
    }

    @Test
    public void test_head_requests_pass_through() throws Exception {
        when(context.method()).thenReturn("HEAD");
        Payload payload = csrfFilter.apply("/api/users", context, nextFilter);
        assertThat(payload).isSameAs(next);
    }

    @Test
    public void test_post_without_session_cookie_passes_through() throws Exception {
        when(context.method()).thenReturn("POST");
        // No _ds_session_id cookie
        Payload payload = csrfFilter.apply("/api/users", context, nextFilter);
        assertThat(payload).isSameAs(next);
    }

    @Test
    public void test_post_with_valid_csrf_token_passes_through() throws Exception {
        when(context.method()).thenReturn("POST");
        String token = "abc123";
        when(context.cookies()).thenReturn(new SimpleCookies() {{
            put("_ds_session_id", new NewCookie("_ds_session_id", "session_value"));
            put("_ds_csrf_token", new NewCookie("_ds_csrf_token", token));
        }});
        when(context.header("X-DS-CSRF-TOKEN")).thenReturn(token);

        Payload payload = csrfFilter.apply("/api/users", context, nextFilter);
        assertThat(payload).isSameAs(next);
    }

    @Test
    public void test_post_with_missing_csrf_header_returns_403() throws Exception {
        when(context.method()).thenReturn("POST");
        when(context.cookies()).thenReturn(new SimpleCookies() {{
            put("_ds_session_id", new NewCookie("_ds_session_id", "session_value"));
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
        when(context.cookies()).thenReturn(new SimpleCookies() {{
            put("_ds_session_id", new NewCookie("_ds_session_id", "session_value"));
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
            when(context.cookies()).thenReturn(new SimpleCookies() {{
                put("_ds_session_id", new NewCookie("_ds_session_id", "session_value"));
            }});

            Payload payload = csrfFilter.apply("/api/users", context, nextFilter);
            assertThat(payload.code()).as("Expected 403 for method " + method).isEqualTo(403);
        }
    }

    @Test
    public void test_csrf_cookie_generation() {
        String token = CsrfFilter.generateToken();
        assertThat(token).isNotNull();
        assertThat(token).isNotEmpty();

        NewCookie cookie = CsrfFilter.csrfCookie(token);
        assertThat(cookie.name()).isEqualTo("_ds_csrf_token");
        assertThat(cookie.value()).isEqualTo(token);
        assertThat(cookie.path()).isEqualTo("/");
        assertThat(cookie.isHttpOnly()).isFalse();
    }

    @Test
    public void test_post_with_session_cookie_but_no_csrf_cookie_returns_403() throws Exception {
        when(context.method()).thenReturn("POST");
        when(context.cookies()).thenReturn(new SimpleCookies() {{
            put("_ds_session_id", new NewCookie("_ds_session_id", "session_value"));
        }});
        when(context.header("X-DS-CSRF-TOKEN")).thenReturn("some_token");

        Payload payload = csrfFilter.apply("/api/users", context, nextFilter);
        assertThat(payload.code()).isEqualTo(403);
    }
}
