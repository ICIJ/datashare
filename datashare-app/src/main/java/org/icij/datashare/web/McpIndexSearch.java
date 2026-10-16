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
import java.util.Map;

/**
 * The Elasticsearch access the MCP tools share: one named project, readable by the caller, and an answer no shard
 * failed to produce.
 */
class McpIndexSearch {
    private static final ObjectMapper MAPPER = JsonObjectMapper.getMapper();
    private final Indexer indexer;

    @Inject
    McpIndexSearch(Indexer indexer) {
        this.indexer = indexer;
    }

    // The path is sent to Elasticsearch as is, not through IndexResource: its URL builder appends the incoming
    // request's query string, and the MCP request's query string must never reach Elasticsearch.
    JsonNode search(String project, Map<String, Object> body, Context context) throws IOException {
        String path = project + "/_search";
        requireReadable(project, path, context);
        return requireAllShards(MAPPER.readTree(indexer.executeRaw("POST", path, MAPPER.writeValueAsString(body))));
    }

    static String requireSingleProject(JsonNode arguments) {
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
}
