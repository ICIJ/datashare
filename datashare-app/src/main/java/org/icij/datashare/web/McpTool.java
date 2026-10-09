package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import net.codestory.http.Context;
import java.util.Map;

public record McpTool(String name, String description, Map<String, Object> inputSchema, Handler handler) {
    @FunctionalInterface
    public interface Handler {
        Object call(JsonNode arguments, Context context) throws Exception;
    }
}
