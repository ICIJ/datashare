package org.icij.datashare.session;

import org.icij.datashare.PropertiesProvider;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class RedisSessionManagerTest {
    RedisSessionManager sessionManager = new RedisSessionManager(new PropertiesProvider(new HashMap<String, String>() {{
        put("messageBusAddress", "redis");
        put("sessionTtlSeconds", "1");
    }}));

    @Test
    public void test_get_set_session() throws Exception {
        Map<String, String> sessionMap = new HashMap<String, String>() {{ put("key", "val");}};
        sessionManager.createSession("id", sessionMap);

        assertThat(sessionManager.getSession("id")).isEqualTo(sessionMap);
    }

    @Test
    public void test_session_ttl() throws Exception {
        Map<String, String> sessionMap = new HashMap<String, String>() {{ put("key", "val");}};
        sessionManager.createSession("id", sessionMap);
        Thread.sleep(2000); // BT: beurk this test is not deterministic. I don't see how to test it differently

        assertThat(sessionManager.getSession("id")).isEmpty();
    }
}