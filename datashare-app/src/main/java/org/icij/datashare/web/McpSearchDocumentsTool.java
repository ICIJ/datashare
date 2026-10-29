package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.errors.UnauthorizedException;
import org.elasticsearch.client.ResponseException;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.icij.datashare.utils.IndexAccessVerifier;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.INDEX_MAX_RESULT_WINDOW;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class McpSearchDocumentsTool implements McpTool {
    private static final ObjectMapper MAPPER = JsonObjectMapper.getMapper();
    private static final McpArguments.IntArgument SIZE = new McpArguments.IntArgument("size", 10, 1, 50);
    // The last hit a search can answer for is the one before index.max_result_window, so a from of the window
    // itself is out of range whatever the size, and is named as from rather than as the pair.
    private static final McpArguments.IntArgument FROM =
            new McpArguments.IntArgument("from", 0, 0, INDEX_MAX_RESULT_WINDOW - 1);
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

    // The path is sent to Elasticsearch as is, not through IndexResource: its URL builder appends the incoming request's
    // query string, and the MCP request's query string must never reach Elasticsearch.
    private JsonNode search(String project, Map<String, Object> body, Context context) throws IOException {
        String path = project + "/_search";
        requireReadable(project, path, context);
        return requireAllShards(MAPPER.readTree(runSearch(path + preference(context), body)));
    }

    // Elasticsearch answers a malformed query_string with a 400 whose message carries the cluster address, so a
    // rejected query is reported as bad input instead. Anything else is a real failure and keeps its own path.
    private String runSearch(String path, Map<String, Object> body) throws IOException {
        try {
            return indexer.executeRaw("POST", path, MAPPER.writeValueAsString(body));
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() != HttpStatus.BAD_REQUEST) {
                throw e;
            }
            throw McpToolException.InvalidArgument.invalid("query");
        }
    }

    // Elasticsearch breaks a tie on _score with an internal doc id that a shard's copies number differently, so two
    // pages answered by different copies can repeat a hit and never show another. Sending the caller's own id as the
    // preference keeps their pages on one copy, without pinning every caller to the same one.
    private static String preference(Context context) {
        return "?preference=" + URLEncoder.encode(((User) context.currentUser()).id, StandardCharsets.UTF_8);
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
