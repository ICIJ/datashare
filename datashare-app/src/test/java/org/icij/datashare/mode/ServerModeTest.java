package org.icij.datashare.mode;

import net.codestory.http.filters.Filter;
import net.codestory.http.security.SessionIdStore;
import org.icij.datashare.cli.AuthMode;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.session.*;
import org.junit.Test;

import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;

public class ServerModeTest {
    @Test
    public void test_server_mode_default_auth_class() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
        }});
        assertThat(mode.get(Filter.class)).isInstanceOf(OAuth2CookieFilter.class);
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
        assertThat(mode.get(Filter.class)).isInstanceOf(OAuth2CookieFilter.class);
    }

    @Test
    public void test_server_mode_users_class() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
            put("authUsersProvider", "org.icij.datashare.session.UsersInDb");
        }});
        assertThat(mode.get(UsersWritable.class)).isInstanceOf(UsersInDb.class);
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
}
