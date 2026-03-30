package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.Cookie;
import net.codestory.http.Cookies;
import net.codestory.http.NewCookie;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.User;
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
    public void test_get_does_not_set_csrf_cookie_on_non_200_response() throws Exception {
        when(context.method()).thenReturn("GET");
        when(context.currentUser()).thenReturn(mock(User.class));
        when(context.cookies()).thenReturn(new SimpleCookies());

        Payload payload = csrfFilter.apply("/api/users", context, () -> new Payload(401));
        assertThat(payload.code()).isEqualTo(401);
        assertThat(payload.cookies()).isEmpty();
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
}
