package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.User;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.text.Project;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class YesCookieAuthFilterTest {
    @Mock JooqRepository jooqRepository;
    private Payload next = Payload.ok();
    private PayloadSupplier nextFilter = () -> next;
    private Context context = mock(Context.class);
    private ArgumentCaptor<User> user = forClass(User.class);


    @Before
    public void setUp() {
        initMocks(this);
    }

    @Test
    public void test_matches() {
        when(jooqRepository.getProjects()).thenReturn(new ArrayList<>());
        PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of("protectedUrlPrefix", "prefix"));
        YesCookieAuthFilter filter = new YesCookieAuthFilter(propertiesProvider, jooqRepository);
        assertThat(filter.matches("foo", null)).isFalse();
        assertThat(filter.matches("prefix", null)).isTrue();
    }

    @Test
    public void test_adds_new_user_to_context() throws Exception {
        when(jooqRepository.getProjects()).thenReturn(new ArrayList<>());
        PropertiesProvider propertiesProvider = new PropertiesProvider();
        propertiesProvider.getProperties().put("defaultProject", "demo");
        propertiesProvider.getProperties().put("messageBusAddress", "redis://redis:6379");
        YesCookieAuthFilter filter = new YesCookieAuthFilter(propertiesProvider, jooqRepository);
        Payload payload = filter.apply("url", context, nextFilter);

        assertThat(payload).isSameAs(next);
        verify(context).setCurrentUser(user.capture());
        assertThat(user.getValue().login()).isNotEmpty();
        assertThat(((DatashareUser)user.getValue()).getProjectNames()).contains("demo");
        assertThat(user.getValue().isInRole("local")).isFalse();
    }

    @Test
    public void test_adds_new_user_to_context_with_database_projects() throws Exception {
        when(jooqRepository.getProjects()).thenReturn(new ArrayList<>(List.of(new Project("foo"))));
        PropertiesProvider propertiesProvider = new PropertiesProvider();
        propertiesProvider.getProperties().put("defaultProject", "demo");
        propertiesProvider.getProperties().put("messageBusAddress", "redis://redis:6379");
        YesCookieAuthFilter filter = new YesCookieAuthFilter(propertiesProvider, jooqRepository);
        Payload payload = filter.apply("url", context, nextFilter);

        assertThat(payload).isSameAs(next);
        verify(context).setCurrentUser(user.capture());
        assertThat(user.getValue().login()).isNotEmpty();
        assertThat(((DatashareUser)user.getValue()).getProjectNames()).contains("demo");
        assertThat(((DatashareUser)user.getValue()).getProjectNames()).contains("foo");
        assertThat(user.getValue().isInRole("local")).isFalse();
    }
}
