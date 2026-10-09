package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.codestory.http.errors.NotFoundException;
import net.codestory.http.filters.basic.BasicAuthFilter;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.web.errors.ForbiddenException;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.user.User.localUser;

public class McpResourceTest extends AbstractProdWebServerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Object> NO_ARGS = Map.of("type", "object", "properties", Map.of());

    private void serve(List<McpTool> tools) {
        configure(routes -> routes.add(new McpResource(tools))
                .filter(new BasicAuthFilter("/", "icij", DatashareUser.singleUser(localUser("local", "foo")))));
    }

    private JsonNode rpc(String body) throws Exception {
        return MAPPER.readTree(post("/api/mcp", body).withPreemptiveAuthentication("local", "").response().content());
    }

    private JsonNode call(String tool, String arguments) throws Exception {
        return rpc("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                   + "\",\"arguments\":" + arguments + "}}");
    }

    @Test
    public void test_initialize() throws Exception {
        serve(List.of());
        JsonNode response = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertThat(response.at("/result/serverInfo/name").asText()).isEqualTo("datashare");
        assertThat(response.at("/result/capabilities/tools").isObject()).isTrue();
    }

    @Test
    public void test_initialized_notification_is_accepted_without_body() {
        serve(List.of());
        net.codestory.rest.Response response = post("/api/mcp", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                .withPreemptiveAuthentication("local", "").response();
        assertThat(response.code()).isEqualTo(202);
        assertThat(response.content()).isEmpty();
    }

    @Test
    public void test_tools_list() throws Exception {
        serve(List.of(new McpTool("echo", "Echoes", NO_ARGS, (args, context) -> args)));
        JsonNode response = rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertThat(response.at("/result/tools/0/name").asText()).isEqualTo("echo");
        assertThat(response.at("/result/tools/0/description").asText()).isEqualTo("Echoes");
        assertThat(response.at("/result/tools/0/inputSchema/type").asText()).isEqualTo("object");
    }

    @Test
    public void test_unknown_method() throws Exception {
        serve(List.of());
        JsonNode response = rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\"}");
        assertThat(response.at("/error/code").asInt()).isEqualTo(-32601);
        assertThat(response.at("/id").asInt()).isEqualTo(3);
    }

    @Test
    public void test_malformed_json() throws Exception {
        serve(List.of());
        JsonNode response = rpc("{\"jsonrpc\":");
        assertThat(response.at("/error/code").asInt()).isEqualTo(-32700);
        assertThat(response.get("id").isNull()).isTrue();
    }

    @Test
    public void test_non_object_body_is_invalid_request() throws Exception {
        serve(List.of());
        JsonNode response = rpc("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}]");
        assertThat(response.at("/error/code").asInt()).isEqualTo(-32600);
    }

    @Test
    public void test_call_returns_one_text_block_with_compact_json() throws Exception {
        serve(List.of(new McpTool("echo", "Echoes", NO_ARGS, (args, context) -> Map.of("a", 1))));
        JsonNode response = call("echo", "{}");
        assertThat(response.at("/result/isError").asBoolean()).isFalse();
        assertThat(response.at("/result/content/0/type").asText()).isEqualTo("text");
        assertThat(response.at("/result/content/0/text").asText()).isEqualTo("{\"a\":1}");
    }

    @Test
    public void test_call_unknown_tool() throws Exception {
        serve(List.of());
        assertThat(call("nope", "{}").at("/error/code").asInt()).isEqualTo(-32602);
    }

    @Test
    public void test_call_with_invalid_argument() throws Exception {
        serve(List.of(new McpTool("t", "", NO_ARGS, (args, context) -> McpResource.requireText(args, "project"))));
        assertThat(call("t", "{}").at("/error/code").asInt()).isEqualTo(-32602);
    }

    @Test
    public void test_call_forbidden_is_a_tool_error() throws Exception {
        serve(List.of(new McpTool("t", "", NO_ARGS, (args, context) -> { throw new ForbiddenException("denied"); })));
        JsonNode response = call("t", "{\"project\":\"bar\"}");
        assertThat(response.at("/result/isError").asBoolean()).isTrue();
        assertThat(response.at("/result/content/0/text").asText()).isEqualTo("forbidden: bar");
    }

    @Test
    public void test_call_not_found_is_a_tool_error() throws Exception {
        serve(List.of(new McpTool("t", "", NO_ARGS, (args, context) -> { throw new NotFoundException(); })));
        JsonNode response = call("t", "{\"project\":\"foo\",\"id\":\"doc1\"}");
        assertThat(response.at("/result/isError").asBoolean()).isTrue();
        assertThat(response.at("/result/content/0/text").asText()).isEqualTo("not found: doc1");
    }

    @Test
    public void test_call_other_exception_is_a_tool_error_with_its_message() throws Exception {
        serve(List.of(new McpTool("t", "", NO_ARGS, (args, context) -> { throw new IllegalStateException("boom"); })));
        JsonNode response = call("t", "{}");
        assertThat(response.at("/result/isError").asBoolean()).isTrue();
        assertThat(response.at("/result/content/0/text").asText()).isEqualTo("boom");
    }

    @Test
    public void test_get_is_not_allowed() {
        serve(List.of());
        assertThat(get("/api/mcp").withPreemptiveAuthentication("local", "").response().code()).isEqualTo(405);
    }

    @Test
    public void test_ping_returns_an_empty_result() throws Exception {
        serve(List.of());
        JsonNode response = rpc("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}");
        assertThat(response.get("result").isObject()).isTrue();
        assertThat(response.get("result").size()).isEqualTo(0);
    }
}
