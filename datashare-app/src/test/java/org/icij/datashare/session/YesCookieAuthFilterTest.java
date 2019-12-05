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

public class YesCookieAuthFilterTest {
    private Payload next = Payload.ok();
    private PayloadSupplier nextFilter = () -> next;
    private Context context = mock(Context.class);
    private ArgumentCaptor<User> user = forClass(User.class);

    @Test
    public void test_matches() {
        YesCookieAuthFilter filter = new YesCookieAuthFilter(new PropertiesProvider(new HashMap<String, String>() {{
            put("protectedUrPrefix", "prefix");
        }}));
        assertThat(filter.matches("foo", null)).isFalse();
        assertThat(filter.matches("prefix", null)).isTrue();
    }

    @Test
     public void test_adds_new_user_to_context() throws Exception {
        YesCookieAuthFilter filter = new YesCookieAuthFilter(new PropertiesProvider(new HashMap<String, String>() {{
            put("defaultProject", "demo");
            put("messageBusAddress", "redis");
        }}));
         Payload payload = filter.apply("url", context, nextFilter);

         assertThat(payload).isSameAs(next);
         verify(context).setCurrentUser(user.capture());
         assertThat(user.getValue().login()).isNotEmpty();
         assertThat(((HashMapUser)user.getValue()).getProjects()).containsExactly("demo");
         assertThat(user.getValue().isInRole("local")).isFalse();
     }

}
