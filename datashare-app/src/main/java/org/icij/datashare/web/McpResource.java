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
import net.codestory.http.errors.NotFoundException;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.asynctasks.UnknownTask;
import org.icij.datashare.web.errors.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Singleton
@Prefix("/api/mcp")
public class McpResource {
    static final String PROTOCOL_VERSION = "2025-06-18";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(McpResource.class);
    private final List<McpTool> tools;

    @Inject
    public McpResource() {
        this(List.of());
    }

    McpResource(List<McpTool> tools) {
        this.tools = tools;
    }

    @Post()
    public Payload handle(Context context) throws IOException {
        JsonNode request;
        try {
            request = MAPPER.readTree(context.request().content());
        } catch (JsonProcessingException e) {
            return json(error(NullNode.getInstance(), -32700, "Parse error"));
        }
        if (request == null || !request.isObject()) {
            return json(error(NullNode.getInstance(), -32600, "Invalid Request"));
        }
        JsonNode id = request.path("id");
        String method = request.path("method").asText();
        if (method.startsWith("notifications/")) {
            return new Payload(202);
        }
        return json(switch (method) {
            case "initialize" -> result(id, Map.of("protocolVersion", PROTOCOL_VERSION, "serverInfo",
                                                   Map.of("name", "datashare", "version", version()), "capabilities",
                                                   Map.of("tools", Map.of())));
            case "tools/list" -> result(id, Map.of("tools", tools.stream()
                                                                 .map(tool -> Map.of("name", tool.name(), "description",
                                                                                     tool.description(), "inputSchema",
                                                                                     tool.inputSchema())).toList()));
            case "tools/call" -> callTool(id, request.path("params"), context);
            default -> error(id, -32601, "Method not found: " + method);
        });
    }

    private Map<String, Object> callTool(JsonNode id, JsonNode params, Context context) {
        String name = params.path("name").asText();
        JsonNode args = params.path("arguments");
        McpTool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
        if (tool == null) {
            return error(id, -32602, "Unknown tool: " + name);
        }
        try {
            return result(id, toolResult(MAPPER.writeValueAsString(tool.handler().call(args, context)), false));
        } catch (ForbiddenException | UnauthorizedException e) {
            return result(id, toolResult("forbidden: " + subject(args, "project"), true));
        } catch (NotFoundException | UnknownTask e) {
            return result(id, toolResult("not found: " + subject(args, "id"), true));
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        } catch (Exception e) {
            logger.error("MCP tool {} failed", name, e);
            return result(id, toolResult(String.valueOf(e.getMessage()), true));
        }
    }

    static String requireText(JsonNode args, String name) {
        String value = args.path(name).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing argument: " + name);
        }
        return value;
    }

    static int intArg(JsonNode args, String name, int defaultValue, int min, int max) {
        JsonNode node = args.path(name);
        if (node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        if (!node.canConvertToInt() || node.asInt() < min || node.asInt() > max) {
            throw new IllegalArgumentException("invalid argument: " + name);
        }
        return node.asInt();
    }

    private static String subject(JsonNode args, String fallback) {
        return args.has("taskId") ? args.get("taskId").asText() : args.path(fallback).asText();
    }

    private static Map<String, Object> toolResult(String text, boolean isError) {
        return Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", isError);
    }

    private static Map<String, Object> result(JsonNode id, Object result) {
        return message(id, "result", result);
    }

    private static Map<String, Object> error(JsonNode id, int code, String message) {
        return message(id, "error", Map.of("code", code, "message", message));
    }

    private static Map<String, Object> message(JsonNode id, String key, Object value) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put(key, value);
        return message;
    }

    private static Payload json(Object body) throws IOException {
        return new Payload("application/json", MAPPER.writeValueAsString(body));
    }

    private static String version() throws IOException {
        return RootResource.getDatashareVersion().getProperty("git.build.version", "unknown");
    }
}
