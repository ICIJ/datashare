package org.icij.datashare.mode;

import com.google.inject.Injector;
import net.codestory.http.filters.Filter;
import net.codestory.http.security.SessionIdStore;
import org.icij.datashare.session.*;
import org.junit.Test;

import java.util.HashMap;

import static com.google.inject.Guice.createInjector;
import static org.fest.assertions.Assertions.assertThat;

public class ServerModeTest {
    @Test
    public void test_server_mode_default_auth_class() {
        Injector injector = createInjector(new ServerMode(new HashMap<>()));
        assertThat(injector.getInstance(Filter.class)).isInstanceOf(OAuth2CookieFilter.class);
    }

    @Test
    public void test_server_mode_auth_class() {
        Injector injector = createInjector(new ServerMode(new HashMap<String, String>() {{
            put("authFilter", "org.icij.datashare.session.BasicAuthAdaptorFilter");
        }}));
        assertThat(injector.getInstance(Filter.class)).isInstanceOf(BasicAuthAdaptorFilter.class);
    }

    @Test
    public void test_server_mode_bad_auth_class_uses_default() {
        Injector injector = createInjector(new ServerMode(new HashMap<String, String>() {{
            put("authFilter", "org.icij.UnknownClass");
        }}));
        assertThat(injector.getInstance(Filter.class)).isInstanceOf(OAuth2CookieFilter.class);
    }

    @Test
    public void test_server_mode_users_class() {
        Injector injector = createInjector(new ServerMode(new HashMap<String, String>() {{
            put("authUsersProvider", "org.icij.datashare.session.UsersInDb");
        }}));
        assertThat(injector.getInstance(UsersWritable.class)).isInstanceOf(UsersInDb.class);
    }

    @Test
    public void test_server_bad_users_class_uses_default() {
        Injector injector = createInjector(new ServerMode(new HashMap<String, String>() {{
            put("authUsersProvider", "org.icij.UnknownClass");
        }}));
        assertThat(injector.getInstance(UsersWritable.class)).isInstanceOf(UsersInRedis.class);
    }

    @Test
    public void test_session_store_redis() {
        Injector injector = createInjector(new ServerMode(new HashMap<String, String>() {{
            put("sessionStoreType", "redis");
        }}));
        assertThat(injector.getInstance(SessionIdStore.class)).isInstanceOf(RedisSessionIdStore.class);
    }

    @Test
    public void test_session_store_memory() {
        Injector injector = createInjector(new ServerMode(new HashMap<String, String>() {{
            put("sessionStoreType", "memory");
        }}));
        assertThat(injector.getInstance(SessionIdStore.class)).isInstanceOf(net.codestory.http.security.SessionIdStore.inMemory().getClass());
    }
}
