package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.errors.UnauthorizedException;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.utils.IndexAccessVerifier;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class McpSearchDocumentsTool implements McpTool {
    private static final ObjectMapper MAPPER = JsonObjectMapper.getMapper();
    private static final McpArguments.IntArgument SIZE = new McpArguments.IntArgument("size", 10, 1, 50);
    private final Indexer indexer;

    @Inject
    public McpSearchDocumentsTool(Indexer indexer) {
        this.indexer = indexer;
    }

    @Override
    public String name() {
        return "search_documents";
    }

    @Override
    public String description() {
        return "Full-text search in the documents of one project.";
    }

    @Override
    public Object call(JsonNode arguments, Context context) throws IOException {
        String project = requireSingleProject(arguments);
        Map<String, Object> body = searchBody(McpArguments.requireText(arguments, "query"), SIZE.readFrom(arguments));
        return McpSearchResult.from(search(project, body, context));
    }

    // The path is sent to Elasticsearch as is, not through IndexResource: its URL builder appends the incoming request's
    // query string, and the MCP request's query string must never reach Elasticsearch.
    private JsonNode search(String project, Map<String, Object> body, Context context) throws IOException {
        String path = project + "/_search";
        requireReadable(project, path, context);
        JsonNode response = MAPPER.readTree(indexer.executeRaw("POST", path, MAPPER.writeValueAsString(body)));
        return requireAllShards(response);
    }

    private static String requireSingleProject(JsonNode arguments) {
        String project = McpArguments.requireText(arguments, "project");
        if (!isSingleIndexName(project)) {
            throw McpToolException.InvalidArgument.invalid("project");
        }
        return project;
    }

    // checkIndices signals a malformed index list with IllegalArgumentException; here it only means "not one index".
    private static boolean isSingleIndexName(String project) {
        try {
            return !IndexAccessVerifier.checkIndices(project).contains(",");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void requireReadable(String project, String path, Context context) {
        try {
            IndexAccessVerifier.checkPath(path, context);
        } catch (UnauthorizedException e) {
            throw new McpToolException.Forbidden(project, e);
        }
    }

    private static JsonNode requireAllShards(JsonNode response) {
        int failedShards = response.at("/_shards/failed").asInt();
        if (failedShards > 0) {
            throw new McpToolException("search failed on " + failedShards + " shard(s)");
        }
        return response;
    }

    private static Map<String, Object> searchBody(String query, int size) {
        Map<String, Object> highlightContent =
                Map.of("max_analyzed_offset", 999999, "fragment_size", 280, "number_of_fragments", 2);
        Map<String, Object> documentsMatchingQuery =
                Map.of("must", Map.of("query_string", Map.of("query", query)), "filter",
                       Map.of("term", Map.of("type", "Document")));
        return Map.of("size", size, "_source", List.of("path", "contentType"), "query",
                      Map.of("bool", documentsMatchingQuery), "highlight",
                      Map.of("fields", Map.of("content", highlightContent)));
    }
}
