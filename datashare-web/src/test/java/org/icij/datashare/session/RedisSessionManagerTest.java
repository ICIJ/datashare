package org.icij.datashare.session;

import org.icij.datashare.PropertiesProvider;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class RedisSessionManagerTest {
    RedisSessionManager sessionManager = new RedisSessionManager(new PropertiesProvider(new HashMap<String, String>() {{
        put("messageBusAddress", "redis");
    }}));

    @Test
    public void test_get_set_session() throws Exception {
        Map<String, String> sessionMap = new HashMap<String, String>() {{ put("key", "val");}};
        sessionManager.createSession("id", sessionMap);

        assertThat(sessionManager.getSession("id")).isEqualTo(sessionMap);
    }
}