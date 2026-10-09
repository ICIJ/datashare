package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import net.codestory.http.Context;
import java.util.Map;

/**
 * One tool exposed on {@code POST /api/mcp}. Its result is serialized like a REST response body. A failure the client
 * should see as a tool error is a {@link McpToolException}; anything else is logged as a server error.
 */
public interface McpTool {
    Map<String, Object> NO_ARGUMENTS = Map.of("type", "object", "properties", Map.of());

    String name();

    String description();

    Map<String, Object> inputSchema();

    Object call(JsonNode arguments, Context context) throws Exception;
}
