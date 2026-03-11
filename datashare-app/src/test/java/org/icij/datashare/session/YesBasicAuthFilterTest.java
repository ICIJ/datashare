package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * useful for the mockup
 */
public class YesBasicAuthFilterTest {
    private YesBasicAuthFilter filter;

    private final Payload next = Payload.ok();
    private final PayloadSupplier nextFilter = () -> next;
    private final Context context = mock(Context.class);
    private final ArgumentCaptor<DatashareUser> user = forClass(DatashareUser.class);

    private final PostLoginEnroller enroller = mock(PostLoginEnroller.class);

    @Before
    public void create_filter() {
      filter = new YesBasicAuthFilter(new PropertiesProvider(new HashMap<>() {{
          put("protectedUriPrefix", "secure");
      }}), enroller);
    }

    @Test
    public void test_reject_secure_request_without_user() throws Exception {
        Payload payload = filter.apply("/secure/uri", context, nextFilter);

        assertThat(payload.code()).isEqualTo(401);
        assertThat(payload.headers()).includes(entry("WWW-Authenticate", "Basic realm=\"datashare\""));
    }

    @Test
    public void test_pass_with_user_information() throws Exception {
        when(context.header("Authorization")).thenReturn("Basic Zm9vOmJhcg=="); // "foo:bar" base64 encoded
        Payload payload = filter.apply("/secure/uri", context, nextFilter);

        assertThat(payload).isSameAs(next);
        verify(context).setCurrentUser(user.capture());
        assertThat(user.getValue().login()).isEqualTo("foo");
    }

    @Test
    public void test_enroll_is_called_after_basic_auth() throws Exception {
        when(context.currentUser()).thenReturn(new DatashareUser("foo"));
        when(context.header("Authorization")).thenReturn("Basic Zm9vOmJhcg=="); // foo:bar

        filter.apply("/secure/uri", context, nextFilter);

        verify(enroller).enroll(any(DatashareUser.class));
    }
}
