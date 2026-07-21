package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.User;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.text.Project;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class YesCookieAuthFilterTest {
    @Mock Repository repository;
    private Payload next = Payload.ok();
    private PayloadSupplier nextFilter = () -> next;
    private Context context = mock(Context.class);
    private ArgumentCaptor<User> user = forClass(User.class);
    private final List<UsersIdProviderRedisCache> usersToClose = new ArrayList<>();
    private final List<RedisSessionIdStore> sessionIdStoresToClose = new ArrayList<>();

    @Before
    public void setUp() {
        initMocks(this);
    }

    @After
    public void tearDown() {
        usersToClose.forEach(UsersIdProviderRedisCache::close);
        sessionIdStoresToClose.forEach(RedisSessionIdStore::close);
    }

    private YesCookieAuthFilter createYesCookieAuthFilter(PropertiesProvider propertiesProvider) {
        return createYesCookieAuthFilter(propertiesProvider, null);
    }

    private YesCookieAuthFilter createYesCookieAuthFilter(PropertiesProvider propertiesProvider, PostLoginEnroller enroller) {
        UsersIdProviderRedisCache usersIdProviderCache = new UsersIdProviderRedisCache(propertiesProvider);
        RedisSessionIdStore sessionIdStore = new RedisSessionIdStore(propertiesProvider);
        usersToClose.add(usersIdProviderCache);
        sessionIdStoresToClose.add(sessionIdStore);
        return new YesCookieAuthFilter(propertiesProvider, usersIdProviderCache, sessionIdStore, repository, enroller);
    }

    @Test
    public void test_matches() {
        when(repository.getProjects()).thenReturn(new ArrayList<>());
        PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of("protectedUrlPrefix", "prefix"));
        YesCookieAuthFilter filter = createYesCookieAuthFilter(propertiesProvider);
        assertThat(filter.matches("foo", null)).isFalse();
        assertThat(filter.matches("prefix", null)).isTrue();
    }

    @Test
    public void test_matches_api_paths_with_static_file_extensions() {
        when(repository.getProjects()).thenReturn(new ArrayList<>());
        PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of("protectedUrlPrefix", "/"));
        YesCookieAuthFilter filter = createYesCookieAuthFilter(propertiesProvider);

        assertThat(filter.matches("/api/users/me.css", null)).isTrue();
        assertThat(filter.matches("/api/users/me.js", null)).isTrue();
        assertThat(filter.matches("/api/users/me.png", null)).isTrue();
        assertThat(filter.matches("/api/users/me.ico", null)).isTrue();
        assertThat(filter.matches("/api/key/victim.woff", null)).isTrue();
        assertThat(filter.matches("/api/batch/search.map", null)).isTrue();
    }

    @Test
    public void test_matches_auth_paths_with_static_file_extensions() {
        when(repository.getProjects()).thenReturn(new ArrayList<>());
        PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of("protectedUrlPrefix", "/"));
        YesCookieAuthFilter filter = createYesCookieAuthFilter(propertiesProvider);

        assertThat(filter.matches("/auth/signin", null)).isTrue();
        assertThat(filter.matches("/auth/callback.css", null)).isTrue();
    }

    @Test
    public void test_adds_new_user_to_context() throws Exception {
        when(repository.getProjects()).thenReturn(new ArrayList<>());
        PropertiesProvider propertiesProvider = new PropertiesProvider();
        propertiesProvider.getProperties().put("defaultProject", "demo");
        String redisAddress = EnvUtils.resolveUri("redis", "redis://redis:6379");
        propertiesProvider.getProperties().put("redisAddress", redisAddress);
        YesCookieAuthFilter filter = createYesCookieAuthFilter(propertiesProvider);
        Payload payload = filter.apply("url", context, nextFilter);

        assertThat(payload).isSameAs(next);
        verify(context).setCurrentUser(user.capture());
        assertThat(user.getValue().login()).isNotEmpty();
        assertThat(((DatashareUser)user.getValue()).getProjectNames()).contains("demo");
        assertThat(user.getValue().isInRole("local")).isFalse();
    }

    @Test
    public void test_enroll_is_called_on_new_user_creation() throws Exception {
        PostLoginEnroller enroller = mock(PostLoginEnroller.class);
        when(repository.getProjects()).thenReturn(new ArrayList<>());
        PropertiesProvider props = new PropertiesProvider();
        String redisAddress = EnvUtils.resolveUri("redis", "redis://redis:6379");
        props.getProperties().put("redisAddress", redisAddress);
        YesCookieAuthFilter filter = createYesCookieAuthFilter(props, enroller);

        filter.apply("url", context, nextFilter);

        verify(enroller).enroll(any(DatashareUser.class));
    }

    @Test
    public void test_adds_new_user_to_context_with_database_projects() throws Exception {
        when(repository.getProjects()).thenReturn(new ArrayList<>(List.of(new Project("foo"))));
        PropertiesProvider propertiesProvider = new PropertiesProvider();
        propertiesProvider.getProperties().put("defaultProject", "demo");
        String redisAddress = EnvUtils.resolveUri("redis", "redis://redis:6379");
        propertiesProvider.getProperties().put("redisAddress", redisAddress);
        YesCookieAuthFilter filter = createYesCookieAuthFilter(propertiesProvider);
        Payload payload = filter.apply("url", context, nextFilter);

        assertThat(payload).isSameAs(next);
        verify(context).setCurrentUser(user.capture());
        assertThat(user.getValue().login()).isNotEmpty();
        assertThat(((DatashareUser)user.getValue()).getProjectNames()).contains("demo");
        assertThat(((DatashareUser)user.getValue()).getProjectNames()).contains("foo");
        assertThat(user.getValue().isInRole("local")).isFalse();
    }
}
