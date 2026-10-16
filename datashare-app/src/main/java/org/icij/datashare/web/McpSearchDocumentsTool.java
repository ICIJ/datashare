package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.constants.HttpStatus;
import org.elasticsearch.client.ResponseException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class McpSearchDocumentsTool implements McpTool {
    private static final McpArguments.IntArgument SIZE = new McpArguments.IntArgument("size", 10, 1, 50);
    private final McpIndexSearch index;

    @Inject
    public McpSearchDocumentsTool(McpIndexSearch index) {
        this.index = index;
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
        String project = McpIndexSearch.requireSingleProject(arguments);
        Map<String, Object> body = searchBody(McpArguments.requireText(arguments, "query"), SIZE.readFrom(arguments));
        return McpSearchResult.from(search(project, body, context));
    }

    // Elasticsearch answers a malformed query_string with a 400 whose message carries the cluster address, so a
    // rejected query is reported as bad input instead. Anything else is a real failure and keeps its own path.
    private JsonNode search(String project, Map<String, Object> body, Context context) throws IOException {
        try {
            return index.search(project, body, context);
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() != HttpStatus.BAD_REQUEST) {
                throw e;
            }
            throw McpToolException.InvalidArgument.invalid("query");
        }
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
