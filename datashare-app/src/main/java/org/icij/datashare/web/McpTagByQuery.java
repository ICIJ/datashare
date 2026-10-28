package org.icij.datashare.web;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The counting half of a query selection on tag_documents / untag_documents: resolves how many documents a
 * query_string matches, without writing anything, so the caller can show the count and ask the user to confirm.
 */
class McpTagByQuery {
    private final McpIndexSearch indexSearch;

    @Inject
    McpTagByQuery(McpIndexSearch indexSearch) {
        this.indexSearch = indexSearch;
    }

    // track_total_hits is left at its Elasticsearch default, so a query past the ten-thousand window answers a
    // lower bound rather than paying for an exact count no one asked for.
    Batch resolve(String project, String query, int size, Context context) throws IOException {
        Map<String, Object> documentsMatchingQuery =
                Map.of("must", Map.of("query_string", Map.of("query", query)), "filter",
                       Map.of("term", Map.of("type", "Document")));
        Map<String, Object> body = Map.of("size", size, "_source", false, "query", Map.of("bool",
                                                                                           documentsMatchingQuery));
        JsonNode response = search(project, body, context);
        long matched = response.at("/hits/total/value").asLong();
        boolean lowerBound = "gte".equals(response.at("/hits/total/relation").asText());
        List<String> ids = new ArrayList<>();
        response.at("/hits/hits").forEach(hit -> ids.add(hit.at("/_id").asText()));
        return new Batch(ids, matched, lowerBound);
    }

    // A project whose index does not exist reaches here rather than the grant check, which only knows the user's
    // memberships, same reasoning as McpGetDocumentTool.
    private JsonNode search(String project, Map<String, Object> body, Context context) throws IOException {
        try {
            return indexSearch.search(project, body, context);
        } catch (ElasticsearchException e) {
            if ("index_not_found_exception".equals(indexNotFound(e))) {
                throw new McpToolException.NotFound(project, e);
            }
            throw e;
        }
    }

    private static String indexNotFound(ElasticsearchException e) {
        return e.error() == null ? null : e.error().type();
    }

    /** What a query resolves to: the matching ids this call fetched, and how many the query matches in total. */
    record Batch(List<String> ids, long matched, boolean lowerBound) {}

    static Map<String, Object> confirmation(String action, McpTagDocumentsTool.BatchTag batch, Batch resolved) {
        boolean confirmationRequired = resolved.matched() > 0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", batch.project());
        result.put("query", batch.query());
        result.put("tags", batch.tags());
        result.put("matched", resolved.matched());
        result.put("lower_bound", resolved.lowerBound());
        result.put("confirmation_required", confirmationRequired);
        result.put("message", message(action, batch, resolved, confirmationRequired));
        return result;
    }

    private static String message(String action, McpTagDocumentsTool.BatchTag batch, Batch resolved,
                                   boolean confirmationRequired) {
        if (!confirmationRequired) {
            return "No documents matched the query in project %s, so there is nothing to %s.".formatted(
                    batch.project(), action);
        }
        String count = (resolved.lowerBound() ? "at least " : "") + resolved.matched();
        return ("About to %s %s documents in project %s with %s. Show this count to the user and ask whether to "
                + "proceed. If they agree, call %s_documents again with the same arguments and confirmed: true. "
                + "Nothing has been changed yet.").formatted(action, count, batch.project(), batch.tags(), action);
    }
}
