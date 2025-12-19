package org.icij.datashare.mode;

import net.codestory.http.filters.Filter;
import net.codestory.http.security.SessionIdStore;
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
    public void test_session_store_memory() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", "SERVER");
            put("sessionStoreType", QueueType.MEMORY.name());
        }});
        assertThat(mode.get(SessionIdStore.class)).isInstanceOf(net.codestory.http.security.SessionIdStore.inMemory().getClass());
    }
}
