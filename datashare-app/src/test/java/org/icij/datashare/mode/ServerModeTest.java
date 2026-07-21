package org.icij.datashare.mode;

import net.codestory.http.filters.Filter;
import net.codestory.http.filters.auth.CookieAuthFilter;
import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.http.security.SessionIdStore;
import org.icij.datashare.cli.AuthMode;
import org.icij.datashare.cli.QueueType;
import net.codestory.http.security.Users;
import org.icij.datashare.session.*;
import org.junit.Test;

import org.icij.datashare.PropertiesProvider;

import java.lang.reflect.Field;
import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;

public class ServerModeTest {
    public static class DummyTestFilter implements net.codestory.http.filters.Filter {
        @Override
        public net.codestory.http.payload.Payload apply(String uri, net.codestory.http.Context context, net.codestory.http.filters.PayloadSupplier nextFilter) throws Exception {
            return nextFilter.get();
        }
    }

    @Test
    public void test_server_mode_default_auth_class() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
        }});
        assertThat(mode.get(Filter.class)).isInstanceOf(FormAuthFilter.class);
    }

    @Test
    public void test_server_mode_auth_class() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
            put("authFilter", "org.icij.datashare.session.BasicAuthAdaptorFilter");
        }});
        assertThat(mode.get(Filter.class)).isInstanceOf(BasicAuthAdaptorFilter.class);
    }

    @Test
    public void test_server_mode_bad_auth_class_uses_default() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
            put("authFilter", "org.icij.UnknownClass");
        }});
        assertThat(mode.get(Filter.class)).isInstanceOf(FormAuthFilter.class);
    }

    @Test
    public void test_server_mode_users_class() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
            put("authUsersProvider", "org.icij.datashare.session.UsersInDb");
        }});
        assertThat(mode.get(Users.class)).isInstanceOf(UsersInDb.class);
    }

    @Test
    public void test_session_store_redis() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
            put("sessionStoreType", QueueType.REDIS.name());
        }});
        assertThat(mode.get(SessionIdStore.class)).isInstanceOf(RedisSessionIdStore.class);
    }

    @Test
    public void test_filter_class_for_maps_every_auth_mode() {
        assertThat(ServerMode.filterClassFor(AuthMode.OAUTH)).isEqualTo(OAuth2CookieFilter.class);
        assertThat(ServerMode.filterClassFor(AuthMode.FORM)).isEqualTo(FormAuthFilter.class);
        assertThat(ServerMode.filterClassFor(AuthMode.BASIC)).isEqualTo(BasicAuthAdaptorFilter.class);
        assertThat(ServerMode.filterClassFor(AuthMode.YES_COOKIE)).isEqualTo(YesCookieAuthFilter.class);
        assertThat(ServerMode.filterClassFor(AuthMode.YES_BASIC)).isEqualTo(YesBasicAuthFilter.class);
    }

    @Test
    public void test_session_store_memory() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
            put("sessionStoreType", QueueType.MEMORY.name());
        }});
        assertThat(mode.get(SessionIdStore.class)).isInstanceOf(net.codestory.http.security.SessionIdStore.inMemory().getClass());
    }

    @Test
    public void test_auth_mode_form() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
            put("auth", "form");
        }});
        assertThat(mode.get(Filter.class)).isInstanceOf(FormAuthFilter.class);
    }

    @Test
    public void test_auth_mode_basic() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
            put("auth", "basic");
        }});
        assertThat(mode.get(Filter.class)).isInstanceOf(BasicAuthAdaptorFilter.class);
    }

    @Test
    public void test_auth_mode_wins_over_deprecated_auth_filter() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
            put("auth", "form");
            put("authFilter", "org.icij.datashare.session.BasicAuthAdaptorFilter");
        }});
        assertThat(mode.get(Filter.class)).isInstanceOf(FormAuthFilter.class);
        assertThat(mode.get(PropertiesProvider.class).get("auth").orElse(null)).isEqualTo("form");
    }

    @Test
    public void test_legacy_auth_filter_still_resolves() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
            put("authFilter", "org.icij.datashare.session.BasicAuthAdaptorFilter");
        }});
        assertThat(mode.get(Filter.class)).isInstanceOf(BasicAuthAdaptorFilter.class);
    }

    @Test
    public void test_mode_for_filter_class_maps_known_classes() {
        assertThat(ServerMode.modeForFilterClass(OAuth2CookieFilter.class).get()).isEqualTo(AuthMode.OAUTH);
        assertThat(ServerMode.modeForFilterClass(FormAuthFilter.class).get()).isEqualTo(AuthMode.FORM);
        assertThat(ServerMode.modeForFilterClass(BasicAuthAdaptorFilter.class).get()).isEqualTo(AuthMode.BASIC);
        assertThat(ServerMode.modeForFilterClass(YesCookieAuthFilter.class).get()).isEqualTo(AuthMode.YES_COOKIE);
        assertThat(ServerMode.modeForFilterClass(YesBasicAuthFilter.class).get()).isEqualTo(AuthMode.YES_BASIC);
    }

    @Test
    public void test_mode_for_filter_class_returns_empty_for_unknown_class() {
        assertThat(ServerMode.modeForFilterClass(BasicAuthFilter.class).isPresent()).isFalse();
    }

    @Test
    public void test_default_materializes_form_auth_property() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
        }});
        assertThat(mode.get(PropertiesProvider.class).get("auth").orElse(null)).isEqualTo("form");
    }

    @Test
    public void test_explicit_auth_mode_is_materialized() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
            put("auth", "basic");
        }});
        assertThat(mode.get(PropertiesProvider.class).get("auth").orElse(null)).isEqualTo("basic");
    }

    @Test
    public void test_recognized_legacy_auth_filter_is_mapped_back() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
            put("authFilter", "org.icij.datashare.session.BasicAuthAdaptorFilter");
        }});
        assertThat(mode.get(PropertiesProvider.class).get("auth").orElse(null)).isEqualTo("basic");
    }

    @Test
    public void test_unrecognized_legacy_auth_filter_falls_back_to_form() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
            put("authFilter", "org.icij.UnknownClass");
        }});
        // resolveAuthFilterClass falls back to FormAuthFilter; modeForFilterClass maps it back to "form"
        assertThat(mode.get(PropertiesProvider.class).get("auth").orElse(null)).isEqualTo("form");
    }

    @Test
    public void test_invalid_auth_value_fails_fast() {
        // An unknown `auth` value (this programmatic path bypasses the picocli converter)
        // must make SERVER mode creation fail rather than silently falling back to a default.
        // AuthMode.fromString throws IllegalArgumentException("Unknown auth mode: ...") inside
        // ServerMode.configure(); Guice aborts injector creation and propagates a Throwable.
        Throwable thrown = null;
        try {
            CommonMode.create(new HashMap<>() {{
                put("mode", "SERVER");
                put("auth", "garbage");
            }});
        } catch (Throwable t) {
            thrown = t;
        }
        assertThat(thrown).isNotNull();
    }

    // Addition 1: a custom Filter class that loads successfully but is not one of the five
    // known auth filters, so modeForFilterClass returns empty and "auth" is never written.
    @Test
    public void test_unknown_loadable_auth_filter_leaves_auth_absent() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
            put("authFilter", "org.icij.datashare.mode.ServerModeTest$DummyTestFilter");
        }});
        assertThat(mode.get(PropertiesProvider.class).get("auth").orElse(null)).isNull();
    }

    // Addition 2a: oauth mode materializes "oauth" into the auth property.
    @Test
    public void test_oauth_auth_mode_is_materialized() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
            put("auth", "oauth");
        }});
        assertThat(mode.get(PropertiesProvider.class).get("auth").orElse(null)).isEqualTo("oauth");
    }

    // Addition 2b: yesCookie mode materializes "yesCookie" into the auth property.
    @Test
    public void test_yes_cookie_auth_mode_is_materialized() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
            put("auth", "yesCookie");
        }});
        assertThat(mode.get(PropertiesProvider.class).get("auth").orElse(null)).isEqualTo("yesCookie");
    }

    // Addition 2c: yesBasic mode materializes "yesBasic" into the auth property.
    @Test
    public void test_yes_basic_auth_mode_is_materialized() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
            put("auth", "yesBasic");
        }});
        assertThat(mode.get(PropertiesProvider.class).get("auth").orElse(null)).isEqualTo("yesBasic");
    }

    @Test
    public void test_yes_cookie_auth_filter_reuses_singleton_redis_dependencies() throws Exception {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
            put("auth", "yesCookie");
        }});

        Filter filter = mode.get(Filter.class);
        assertThat(filter).isInstanceOf(YesCookieAuthFilter.class);

        UsersIdProviderCache usersIdProviderCache = mode.get(UsersIdProviderCache.class);
        SessionIdStore sessionIdStore = mode.get(SessionIdStore.class);

        // CookieAuthFilter (codestory-http) stores these as protected fields with no getter,
        // and it's in a different package, so reflection is the only way to reach them from here.
        Field usersField = CookieAuthFilter.class.getDeclaredField("users");
        usersField.setAccessible(true);
        Field sessionIdStoreField = CookieAuthFilter.class.getDeclaredField("sessionIdStore");
        sessionIdStoreField.setAccessible(true);

        assertThat(usersField.get(filter)).isSameAs(usersIdProviderCache);
        assertThat(sessionIdStoreField.get(filter)).isSameAs(sessionIdStore);
    }
}
