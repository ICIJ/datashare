package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.User;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.db.JooqRepository;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class LocalUserFilterTest {
    @Mock JooqRepository jooqRepository;
    private Payload next = Payload.ok();
    private PayloadSupplier nextFilter = () -> next;
    private Context context = mock(Context.class);
    private ArgumentCaptor<User> user = forClass(User.class);

    @Before
    public void setUp() {
        initMocks(this);
        when(jooqRepository.getProjects()).thenReturn(new ArrayList<>());
    }

    @Test
    public void test_matches() {
        PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
            put("protectedUrPrefix", "test");
        }});
        LocalUserFilter localUserFilter = new LocalUserFilter(propertiesProvider, jooqRepository);

        assertThat(localUserFilter.matches("foo", null)).isFalse();
        assertThat(localUserFilter.matches("/foo", null)).isFalse();

        assertThat(localUserFilter.matches("test", null)).isTrue();
        assertThat(localUserFilter.matches("/test", null)).isFalse();
        assertThat(localUserFilter.matches("test_bar", null)).isTrue();
    }

    @Test
    public void test_matches_api_paths_with_static_file_extensions() {
        LocalUserFilter localUserFilter = new LocalUserFilter(new PropertiesProvider(), jooqRepository);

        assertThat(localUserFilter.matches("/api/users/me.css", null)).isTrue();
        assertThat(localUserFilter.matches("/api/users/me.js", null)).isTrue();
        assertThat(localUserFilter.matches("/api/users/me.png", null)).isTrue();
        assertThat(localUserFilter.matches("/api/users/me.ico", null)).isTrue();
        assertThat(localUserFilter.matches("/api/key/victim.woff", null)).isTrue();
        assertThat(localUserFilter.matches("/api/batch/search.map", null)).isTrue();
    }

    @Test
    public void test_matches_auth_paths_with_static_file_extensions() {
        LocalUserFilter localUserFilter = new LocalUserFilter(new PropertiesProvider(), jooqRepository);

        assertThat(localUserFilter.matches("/auth/signin", null)).isTrue();
        assertThat(localUserFilter.matches("/auth/callback.css", null)).isTrue();
    }

    @Test
    public void test_adds_local_user_to_context() throws Exception {
        LocalUserFilter localUserFilter = new LocalUserFilter(new PropertiesProvider(), jooqRepository);
        Payload payload = localUserFilter.apply("url", context, nextFilter);

        assertThat(payload).isSameAs(next);
        verify(context).setCurrentUser(user.capture());
        assertThat(user.getValue().login()).isEqualTo("local");
        assertThat(user.getValue().isInRole("local")).isTrue();
    }

    @Test
    public void test_adds_custom_user_to_context() throws Exception {
        PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
            put("defaultUserName", "foo");
        }});
        LocalUserFilter localUserFilter = new LocalUserFilter(propertiesProvider, jooqRepository);
        Payload payload = localUserFilter.apply("url", context, nextFilter);

        assertThat(payload).isSameAs(next);
        verify(context).setCurrentUser(user.capture());
        assertThat(user.getValue().login()).isEqualTo("foo");
    }

    @Test
    public void test_adds_cookie() throws Exception {
        LocalUserFilter localUserFilter = new LocalUserFilter(new PropertiesProvider(), jooqRepository);
        Payload payload = localUserFilter.apply("url", context, nextFilter);

        assertThat(payload.cookies().size()).isEqualTo(1);
        assertThat(payload.cookies().get(0).name()).isEqualTo("_ds_session_id");
        assertThat(payload.cookies().get(0).value()).contains("\"login\":\"local\"");
    }
}