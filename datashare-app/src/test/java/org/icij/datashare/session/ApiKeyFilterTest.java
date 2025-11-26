package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.Cookies;
import net.codestory.http.NewCookie;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.User;
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
    @Mock private UsersWritable users;
    @Mock private ApiKeyStore apiKeyStore;
    ApiKeyFilter apiKeyFilter;

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

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(context.cookies()).thenReturn(mock(Cookies.class));
        apiKeyFilter = new ApiKeyFilter(users, apiKeyStore);
    }
}
