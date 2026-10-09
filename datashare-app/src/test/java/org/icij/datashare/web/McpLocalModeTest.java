package org.icij.datashare.web;

import org.icij.datashare.EnvUtils;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class McpLocalModeTest extends AbstractProdWebServerTest {
    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";

    @Before
    public void setUp() throws Exception {
        Map<String, Object> properties = new HashMap<>(Map.of(
                "mode", "LOCAL",
                "dataDir", McpLocalModeTest.class.getResource("/data").getPath(),
                "extensionsDir", McpLocalModeTest.class.getResource("/extensions").getPath(),
                "pluginsDir", McpLocalModeTest.class.getResource("/plugins").getPath(),
                "redisAddress", EnvUtils.resolveUri("redis", "redis://redis:6379"),
                "elasticsearchAddress", EnvUtils.resolveUri("elasticsearch", "http://elasticsearch:9200"),
                "messageBusAddress", "amqp://admin:s3cret@rabbitmq:5672"
        ));
        configure(CommonMode.create(properties).createWebConfiguration());
        waitForDatashare();
    }

    @Test
    public void test_post_json_without_csrf_headers_is_accepted() {
        postRaw("/api/mcp", "application/json", INITIALIZE)
                .should().respond(200).contain("\"protocolVersion\"");
    }

    @Test
    public void test_post_non_json_without_csrf_headers_is_forbidden() {
        postRaw("/api/mcp", "text/plain", INITIALIZE).should().respond(403);
    }
}
