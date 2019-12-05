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
import static org.mockito.Mockito.*;

/**
 * useful for the mockup
 */
public class YesBasicAuthFilterTest {
    private YesBasicAuthFilter filter;

    private Payload next = Payload.ok();
    private PayloadSupplier nextFilter = () -> next;
    private Context context = mock(Context.class);
    private ArgumentCaptor<HashMapUser> user = forClass(HashMapUser.class);

    @Before
    public void create_filter() {
      filter = new YesBasicAuthFilter(new PropertiesProvider(new HashMap<String, String>() {{
          put("protectedUrPrefix", "secure");
      }}));
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
}
