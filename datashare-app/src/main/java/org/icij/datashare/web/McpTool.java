package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import net.codestory.http.Context;
import org.icij.datashare.json.JsonObjectMapper;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * One tool exposed on {@code POST /api/mcp}. Its result is serialized like a REST response body. A failure the client
 * should see as a tool error is a {@link McpToolException}; anything else is logged as a server error.
 */
public interface McpTool {
    String name();

    String description();

    Object call(JsonNode arguments, Context context) throws Exception;

    /** The JSON Schema of the arguments, read from {@code mcp/schemas/<name>.json} on the classpath. */
    default JsonNode inputSchema() {
        String path = "/mcp/schemas/%s.json".formatted(name());
        try (InputStream schema = McpTool.class.getResourceAsStream(path)) {
            if (schema == null) {
                throw new FileNotFoundException("no MCP input schema at " + path);
            }
            return JsonObjectMapper.getMapper().readTree(schema);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
