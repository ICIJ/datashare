package org.icij.datashare.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.payload.Payload;
import org.icij.datashare.json.JsonObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static java.util.Objects.requireNonNullElse;
import static org.icij.datashare.web.McpMessages.error;
import static org.icij.datashare.web.McpMessages.json;
import static org.icij.datashare.web.McpMessages.result;
import static org.icij.datashare.web.McpMessages.toolError;
import static org.icij.datashare.web.McpMessages.toolOutput;

@Singleton
@Prefix("/api/mcp")
public class McpResource {
    static final String PROTOCOL_VERSION = "2025-06-18";
    private static final ObjectMapper MAPPER = JsonObjectMapper.getMapper();
    private static final Logger logger = LoggerFactory.getLogger(McpResource.class);
    private final List<McpTool> tools;

    @Inject
    public McpResource(Set<McpTool> tools) {
        this.tools = List.copyOf(tools);
    }

    @Post()
    public Payload handle(Context context) throws IOException {
        Optional<JsonNode> request = readRequest(context);
        if (request.isEmpty()) {
            return json(error(NullNode.getInstance(), -32700, "Parse error"));
        }
        return answer(request.get(), context);
    }

    private Payload answer(JsonNode request, Context context) throws IOException {
        if (!request.isObject()) {
            return json(error(NullNode.getInstance(), -32600, "Invalid Request"));
        }
        String method = request.path("method").asText();
        if (method.startsWith("notifications/")) {
            return new Payload(202);
        }
        return json(dispatch(request.path("id"), method, request.path("params"), context));
    }

    private Map<String, Object> dispatch(JsonNode id, String method, JsonNode params, Context context) throws
            IOException {
        return switch (method) {
            case "initialize" -> result(id, serverDescription());
            case "tools/list" -> result(id, Map.of("tools", tools.stream().map(McpResource::describeTool).toList()));
            case "ping" -> result(id, Map.of());
            case "tools/call" -> callTool(id, params, context);
            default -> error(id, -32601, "Method not found: " + method);
        };
    }

    private Map<String, Object> callTool(JsonNode id, JsonNode params, Context context) {
        String name = params.path("name").asText();
        McpTool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
        if (tool == null) {
            return error(id, -32602, "Unknown tool: " + name);
        }
        return callKnownTool(id, tool, params.path("arguments"), context);
    }

    private Map<String, Object> callKnownTool(JsonNode id, McpTool tool, JsonNode arguments, Context context) {
        try {
            return toolOutput(id, MAPPER.writeValueAsString(tool.call(arguments, context)));
        } catch (McpToolException.InvalidArgument e) {
            return error(id, -32602, e.getMessage());
        } catch (McpToolException.Forbidden | McpToolException.NotFound e) {
            return toolError(id, e.getMessage());
        } catch (Exception e) {
            logger.error("MCP tool {} failed", tool.name(), e);
            return toolError(id, describeFailure(e));
        }
    }

    private static Optional<JsonNode> readRequest(Context context) throws IOException {
        try {
            JsonNode request = MAPPER.readTree(context.request().content());
            return Optional.of(requireNonNullElse(request, NullNode.getInstance()));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static Map<String, Object> serverDescription() throws IOException {
        return Map.of("protocolVersion", PROTOCOL_VERSION, "serverInfo",
                      Map.of("name", "datashare", "version", version()), "capabilities", Map.of("tools", Map.of()));
    }

    private static Map<String, Object> describeTool(McpTool tool) {
        return Map.of("name", tool.name(), "description", tool.description(), "inputSchema", tool.inputSchema());
    }

    private static String describeFailure(Exception exception) {
        return exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
    }

    private static String version() throws IOException {
        return RootResource.getDatashareVersion().getProperty("git.build.version", "unknown");
    }
}
