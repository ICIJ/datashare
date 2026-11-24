package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.Cookie;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.Users;
import org.icij.datashare.PropertiesProvider;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.HashMap;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class BasicAuthAdaptorFilterTest {
    private final Payload next = Payload.ok();
    private final PayloadSupplier nextFilter = () -> next;
    private final Context context = mock(Context.class, RETURNS_DEEP_STUBS);
    @Mock
    Users users;

    @Before
    public void setUp() {
        initMocks(this);
    }

    @Test
    public void test_enroll_is_called_after_successful_auth() throws Exception {
        PostLoginEnroller enroller = mock(PostLoginEnroller.class);
        DatashareUser user = new DatashareUser(new HashMap<>() {{
            put("uid", "alice");
        }});
        when(users.find(eq("alice"), any())).thenReturn(user);
        when(users.find(eq("alice"))).thenReturn(user);
        BasicAuthAdaptorFilter filter = new BasicAuthAdaptorFilter(new PropertiesProvider(), users, enroller);
        when(context.currentUser()).thenReturn(new DatashareUser("alice"));
        when(context.header("Authorization")).thenReturn("Basic YWxpY2U6cGFzcw=="); // alice:pass

        filter.apply("/protected", context, nextFilter);

        verify(enroller).enroll(any(DatashareUser.class));
    }

    @Test
    public void test_enroll_is_not_called_on_signout() throws Exception {
        PostLoginEnroller enroller = mock(PostLoginEnroller.class);
        BasicAuthAdaptorFilter filter = new BasicAuthAdaptorFilter(
                new PropertiesProvider(), mock(Users.class), enroller);

        filter.apply("/auth/signout", context, nextFilter);

        verify(enroller, never()).enroll(any());
    }

    @Test
    public void test_signout_cookie_is_secure_when_the_option_is_set() throws Exception {
        PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of("secureSessionCookie", "true"));
        BasicAuthAdaptorFilter filter = new BasicAuthAdaptorFilter(propertiesProvider, mock(Users.class), null);

        Payload payload = filter.apply("/auth/signout", context, nextFilter);

        Cookie cookie = payload.cookies().get(0);
        assertThat(cookie.isSecure()).isTrue();
    }

    @Test
    public void test_signout_cookie_is_secure_on_https_requests() throws Exception {
        BasicAuthAdaptorFilter filter = new BasicAuthAdaptorFilter(new PropertiesProvider(), mock(Users.class), null);
        when(context.header("x-forwarded-proto")).thenReturn("https");

        Payload payload = filter.apply("/auth/signout", context, nextFilter);

        Cookie cookie = payload.cookies().get(0);
        assertThat(cookie.isSecure()).isTrue();
    }
}
