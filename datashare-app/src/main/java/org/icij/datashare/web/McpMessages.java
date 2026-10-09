package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import net.codestory.http.payload.Payload;
import org.icij.datashare.json.JsonObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON-RPC 2.0 envelopes for the MCP endpoint. */
final class McpMessages {
    private McpMessages() {}

    static Map<String, Object> toolOutput(JsonNode id, String text) {
        return result(id, toolResult(text, false));
    }

    static Map<String, Object> toolError(JsonNode id, String text) {
        return result(id, toolResult(text, true));
    }

    static Map<String, Object> result(JsonNode id, Object result) {
        return message(id, "result", result);
    }

    static Map<String, Object> error(JsonNode id, int code, String message) {
        return message(id, "error", Map.of("code", code, "message", message));
    }

    private static Map<String, Object> toolResult(String text, boolean isError) {
        return Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", isError);
    }

    private static Map<String, Object> message(JsonNode id, String key, Object value) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put(key, value);
        return message;
    }

    static Payload json(Object body) throws IOException {
        return new Payload("application/json", JsonObjectMapper.getMapper().writeValueAsString(body));
    }
}
