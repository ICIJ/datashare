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
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
        return requireAllShards(MAPPER.readTree(runSearch(project, path + preference(context), body)));
    }

    // Elasticsearch breaks a tie on _score with an internal doc id that a shard's copies number differently, so two
    // pages answered by different copies can repeat a hit and never show another. Sending the caller's own id as the
    // preference keeps their pages on one copy, without pinning every caller to the same one.
    private static String preference(Context context) {
        return "?preference=" + URLEncoder.encode(((User) context.currentUser()).id, StandardCharsets.UTF_8);
    }

    // index.refresh_interval is 1s by default, so a search run right after a write can still miss it. A confirmed
    // tag-by-query batch forces a refresh so its own continuation query sees the write it just made.
    void refresh(String project) throws IOException {
        indexer.executeRaw("POST", project + "/_refresh", null);
    }

    // Elasticsearch answers a malformed query_string with a 400 whose message carries the cluster address, so a
    // rejected query is reported as bad input instead. A 404 is the project's index missing, which the grant check
    // cannot catch since it only knows the user's memberships. Anything else is a real failure and keeps its own
    // path.
    private String runSearch(String project, String path, Map<String, Object> body) throws IOException {
        try {
            return indexer.executeRaw("POST", path, MAPPER.writeValueAsString(body));
        } catch (ResponseException e) {
            int status = e.getResponse().getStatusLine().getStatusCode();
            if (status == HttpStatus.BAD_REQUEST) {
                throw McpToolException.InvalidArgument.invalid("query");
            }
            if (status == HttpStatus.NOT_FOUND) {
                throw new McpToolException.NotFound(project, e);
            }
            throw e;
        }
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
