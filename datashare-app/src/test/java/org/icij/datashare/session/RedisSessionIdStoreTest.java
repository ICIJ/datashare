package org.icij.datashare.session;

import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.junit.Test;

import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;

public class RedisSessionIdStoreTest {
    RedisSessionIdStore sessionIdStore = new RedisSessionIdStore(new PropertiesProvider(new HashMap<>() {{
        put("messageBusAddress", EnvUtils.resolveUri("redis", "redis://redis:6379"));
        put("sessionTtlSeconds", "1");
    }}));

    @Test
    public void test_put_get_session() {
        sessionIdStore.put("sid", "login");

        assertThat(sessionIdStore.getLogin("sid")).isEqualTo("login");
    }

    @Test
    public void test_session_ttl() throws Exception {
        sessionIdStore.put("sid", "login");
        Thread.sleep(2000); // BT: beurk this test is not deterministic. I don't see how to test it differently

        assertThat(sessionIdStore.getLogin("sid")).isNull();
    }

    @Test
    public void test_remove_session() throws Exception {
        sessionIdStore.put("sid", "login");
        sessionIdStore.remove("sid");

        assertThat(sessionIdStore.getLogin("id")).isNull();
    }
}
