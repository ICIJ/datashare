package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.payload.Payload;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Singleton
@Prefix("/api/mcp")
public class McpResource {
    static final String PROTOCOL_VERSION = "2025-06-18";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    public McpResource() {
    }

    @Post()
    public Payload handle(Context context) throws IOException {
        JsonNode request = MAPPER.readTree(context.request().content());
        Object result = Map.of("protocolVersion", PROTOCOL_VERSION, "serverInfo",
                               Map.of("name", "datashare", "version", version()), "capabilities",
                               Map.of("tools", Map.of()));
        return json(response(request.get("id"), "result", result));
    }

    private static Map<String, Object> response(JsonNode id, String key, Object value) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put(key, value);
        return response;
    }

    private static Payload json(Object body) throws IOException {
        return new Payload("application/json", MAPPER.writeValueAsString(body));
    }

    private static String version() throws IOException {
        return RootResource.getDatashareVersion().getProperty("git.build.version", "unknown");
    }
}
