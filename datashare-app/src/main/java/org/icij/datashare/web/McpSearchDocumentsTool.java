package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.constants.HttpStatus;
import org.elasticsearch.client.ResponseException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.INDEX_MAX_RESULT_WINDOW;

public class McpSearchDocumentsTool implements McpTool {
    private static final McpArguments.IntArgument SIZE = new McpArguments.IntArgument("size", 10, 1, 50);
    // The last hit a search can answer for is the one before index.max_result_window, so a from of the window
    // itself is out of range whatever the size, and is named as from rather than as the pair.
    private static final McpArguments.IntArgument FROM =
            new McpArguments.IntArgument("from", 0, 0, INDEX_MAX_RESULT_WINDOW - 1);
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
        int from = FROM.readFrom(arguments);
        int size = SIZE.readFrom(arguments);
        // The sum is what the window refuses, so naming only from sends a caller whose size is the problem to the
        // wrong argument.
        if (from + size > INDEX_MAX_RESULT_WINDOW) {
            throw McpToolException.InvalidArgument.invalid("from + size");
        }
        return McpSearchResult.from(
                search(project, searchBody(McpArguments.requireText(arguments, "query"), from, size), context));
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

    private static Map<String, Object> searchBody(String query, int from, int size) {
        Map<String, Object> highlightContent =
                Map.of("max_analyzed_offset", 999999, "fragment_size", 280, "number_of_fragments", 2);
        Map<String, Object> documentsMatchingQuery =
                Map.of("must", Map.of("query_string", Map.of("query", query)), "filter",
                       Map.of("term", Map.of("type", "Document")));
        return Map.of("from", from, "size", size, "_source", List.of("path", "contentType"), "query",
                      Map.of("bool", documentsMatchingQuery), "highlight",
                      Map.of("fields", Map.of("content", highlightContent)));
    }
}
