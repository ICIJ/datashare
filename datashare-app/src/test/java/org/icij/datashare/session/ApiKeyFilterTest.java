package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.Cookies;
import net.codestory.http.NewCookie;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.User;
import net.codestory.http.security.Users;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.*;

public class ApiKeyFilterTest {
    private final Payload next = Payload.ok();
    private final PayloadSupplier nextFilter = () -> next;
    private final Context context = mock(Context.class);
    private final ArgumentCaptor<User> user = forClass(User.class);
    @Mock private Users users;
    @Mock private ApiKeyStore apiKeyStore;
    @Mock
    private PostLoginEnroller postLoginEnroller;
    ApiKeyFilter apiKeyFilter;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(context.cookies()).thenReturn(mock(Cookies.class));
        apiKeyFilter = new ApiKeyFilter(users, apiKeyStore, postLoginEnroller);
    }

    @Test
    public void test_matches() {
        assertThat(apiKeyFilter.matches("/foo", null)).isFalse();
        assertThat(apiKeyFilter.matches("/api", null)).isTrue();
        assertThat(apiKeyFilter.matches("/api_bar", null)).isTrue();
    }

    @Test
    public void test_adds_user_to_context() throws Exception {
        when(context.header("authorization")).thenReturn("Bearer session_id");
        when(apiKeyStore.getLogin("session_id")).thenReturn("user_id");
        when(users.find("user_id")).thenReturn(new DatashareUser("user_id"));

        Payload payload = apiKeyFilter.apply("url", context, nextFilter);

        assertThat(payload).isSameAs(next);
        verify(context).setCurrentUser(user.capture());
        assertThat(user.getValue().login()).isEqualTo("user_id");
    }

    @Test
    public void test_unauthorized_if_type_is_not_bearer() throws Exception {
        when(context.header("authorization")).thenReturn("Basic session_id");

        Payload payload = apiKeyFilter.apply("url", context, nextFilter);

        assertThat(payload.code()).isEqualTo(401);
    }

    @Test
    public void test_unauthorized_if_no_type() throws Exception {
        when(context.header("authorization")).thenReturn("session_id");

        Payload payload = apiKeyFilter.apply("url", context, nextFilter);

        assertThat(payload.code()).isEqualTo(401);
    }

    @Test
    public void test_does_nothing_if_there_is_datashare_cookie() throws Exception {
        when(context.cookies()).thenReturn(new SimpleCookies() {{
            put("_ds_session_id", new NewCookie("_ds_session_id", "cookie value"));
        }});

        Payload payload = apiKeyFilter.apply("url", context, nextFilter);

        assertThat(payload).isSameAs(next);
    }

    @Test
    public void test_enrolls_user_on_valid_api_key() throws Exception {
        DatashareUser dsUser = new DatashareUser("user_id");
        when(context.header("authorization")).thenReturn("Bearer session_id");
        when(apiKeyStore.getLogin("session_id")).thenReturn("user_id");
        when(users.find("user_id")).thenReturn(dsUser);

        apiKeyFilter.apply("url", context, nextFilter);

        verify(postLoginEnroller).enroll(dsUser);
    }

    @Test
    public void test_does_not_enroll_if_enroller_is_null() throws Exception {
        when(context.header("authorization")).thenReturn("Bearer session_id");
        when(apiKeyStore.getLogin("session_id")).thenReturn("user_id");
        when(users.find("user_id")).thenReturn(new DatashareUser("user_id"));
        ApiKeyFilter filterWithoutEnroller = new ApiKeyFilter(users, apiKeyStore, null);

        Payload payload = filterWithoutEnroller.apply("url", context, nextFilter);

        assertThat(payload.code()).isEqualTo(200);
    }

    @Test
    public void test_unauthenticated_api_call_says_authentication_required() throws Exception {
        // ApiKeyFilter sits in front of the auth filters for every /api URI in server mode, so this
        // short circuit, not DatashareAuthFilter, is what an unauthenticated API caller actually
        // hits (see #2444). It used to answer with no body at all.
        Payload payload = apiKeyFilter.apply("/api/users/me", context, nextFilter);

        assertThat(payload.code()).isEqualTo(401);
        assertThat(String.valueOf(payload.rawContent())).contains("authentication required");
    }

    @Test
    public void test_an_unknown_api_key_is_not_distinguishable_from_a_missing_one() throws Exception {
        // same body either way: telling them apart would say whether a key exists
        when(context.header("authorization")).thenReturn("Bearer nope");
        when(apiKeyStore.getLogin("nope")).thenReturn(null);
        Payload withBadKey = apiKeyFilter.apply("/api/users/me", context, nextFilter);

        when(context.header("authorization")).thenReturn(null);
        Payload withNoKey = apiKeyFilter.apply("/api/users/me", context, nextFilter);

        assertThat(String.valueOf(withBadKey.rawContent())).isEqualTo(String.valueOf(withNoKey.rawContent()));
    }
}
