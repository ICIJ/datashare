package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.User;
import org.icij.datashare.PropertiesProvider;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class LocalUserFilterTest {
    private Payload next = Payload.ok();
    private PayloadSupplier nextFilter = () -> next;
    private Context context = mock(Context.class);
    private ArgumentCaptor<User> user = forClass(User.class);

    @Test
    public void test_matches() {
        LocalUserFilter localUserFilter = new LocalUserFilter(new PropertiesProvider(new HashMap<String, String>() {{
            put("protectedUrPrefix", "test");
        }}));

        assertThat(localUserFilter.matches("foo", null)).isFalse();
        assertThat(localUserFilter.matches("/foo", null)).isFalse();

        assertThat(localUserFilter.matches("test", null)).isTrue();
        assertThat(localUserFilter.matches("/test", null)).isFalse();
        assertThat(localUserFilter.matches("test_bar", null)).isTrue();
    }

    @Test
    public void test_adds_local_user_to_context() throws Exception {
        LocalUserFilter localUserFilter = new LocalUserFilter(new PropertiesProvider());
        Payload payload = localUserFilter.apply("url", context, nextFilter);

        assertThat(payload).isSameAs(next);
        verify(context).setCurrentUser(user.capture());
        assertThat(user.getValue().login()).isEqualTo("local");
        assertThat(user.getValue().isInRole("local")).isTrue();
    }

    @Test
    public void test_adds_custom_user_to_context() throws Exception {
        LocalUserFilter localUserFilter = new LocalUserFilter(new PropertiesProvider(new HashMap<String, String>() {{
            put("defaultUserName", "foo");
        }}));
        Payload payload = localUserFilter.apply("url", context, nextFilter);

        assertThat(payload).isSameAs(next);
        verify(context).setCurrentUser(user.capture());
        assertThat(user.getValue().login()).isEqualTo("foo");
    }

    @Test
    public void test_adds_cookie() throws Exception {
        LocalUserFilter localUserFilter = new LocalUserFilter(new PropertiesProvider());
        Payload payload = localUserFilter.apply("url", context, nextFilter);

        assertThat(payload.cookies().size()).isEqualTo(1);
        assertThat(payload.cookies().get(0).name()).isEqualTo("_ds_session_id");
        assertThat(payload.cookies().get(0).value()).contains("\"login\":\"local\"");
    }
}