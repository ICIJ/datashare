package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
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
    private final McpIndexSearch indexSearch;

    @Inject
    public McpSearchDocumentsTool(McpIndexSearch indexSearch) {
        this.indexSearch = indexSearch;
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
        Map<String, Object> body = searchBody(McpArguments.requireText(arguments, "query"), from, size);
        return McpSearchResult.from(indexSearch.search(project, body, context));
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
