package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The counting half of a query selection on tag_documents / untag_documents: resolves how many documents a
 * query_string matches, without writing anything, so the caller can show the count and ask the user to confirm.
 */
class McpTagByQuery {
    private static final Logger logger = LoggerFactory.getLogger(McpTagByQuery.class);
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
        Map<String, Object> body =
                Map.of("size", size, "_source", false, "query", Map.of("bool", documentsMatchingQuery));
        JsonNode response = indexSearch.search(project, body, context);
        long matched = response.at("/hits/total/value").asLong();
        boolean lowerBound = "gte".equals(response.at("/hits/total/relation").asText());
        List<String> ids = new ArrayList<>();
        response.at("/hits/hits").forEach(hit -> ids.add(hit.at("/_id").asText()));
        return new Batch(ids, matched, lowerBound);
    }

    /** The write a confirmed batch performs, with the exception mapping its own tool owns. */
    interface BatchWriter {
        void write(String project, DocumentResource.BatchTagQuery query, Context context) throws IOException;
    }

    // Both tools run the same confirmed batch: size it, resolve the ids, write them, refresh so the continuation
    // query no longer sees them, and answer. Only the write itself and the wording of its failure differ.
    Object writeBatch(String action, McpTagDocumentsTool.BatchTag batch, Context context, BatchWriter writer) throws
            IOException {
        int size = batchSize(batch.tags().size(), "tag".equals(action));
        Batch resolved = resolve(batch.project(), batch.query(), size, context);
        String refreshCaveat = null;
        if (!resolved.ids().isEmpty()) {
            writer.write(batch.project(), batch.writeFor(resolved.ids()), context);
            refreshCaveat = refreshAfterWrite(batch.project(), action);
        }
        return written(action, batch, resolved, size, refreshCaveat);
    }

    // index.refresh_interval delays this call's write from being searchable; refreshing right after it is what
    // lets the continuation query see the batch that was just written instead of repeating it. A refresh failure
    // does not undo a write that already succeeded, so it is a caveat on the message rather than a failed call.
    private String refreshAfterWrite(String project, String action) {
        try {
            indexSearch.refresh(project);
            return null;
        } catch (IOException e) {
            logger.warn("failed to refresh index {} after a confirmed {}-by-query write", project, action, e);
            return "The index refresh failed, so next_query may not be accurate yet; retrying it shortly should " +
                   "catch up.";
        }
    }

    // A label wider than the column it lands in is rejected before the first pass answers, so a caller is not told
    // a count for a write that can only fail. The wording matches DocumentResource, which enforces the same width.
    static void requireTagsWritable(List<String> tags) {
        if (tags.stream().anyMatch(tag -> tag.codePointCount(0, tag.length()) > DocumentResource.MAX_TAG_LENGTH)) {
            throw McpToolException.InvalidArgument.because(
                    "tag longer than %d characters".formatted(DocumentResource.MAX_TAG_LENGTH));
        }
    }

    // The write budget (MAX_TAG_WRITES rows per call) bounds a tagging batch as tags multiply; untagging deletes
    // with two IN lists and does not multiply, so it only keeps the id cap.
    static int batchSize(int tagCount, boolean tagging) {
        return tagging ? Math.min(McpTagDocumentsTool.MAX_DOC_IDS, McpTagDocumentsTool.MAX_TAG_WRITES / tagCount) :
               McpTagDocumentsTool.MAX_DOC_IDS;
    }

    /** What a query resolves to: the matching ids this call fetched, and how many the query matches in total. */
    record Batch(List<String> ids, long matched, boolean lowerBound) {}

    // index.max_result_window caps a search at ten thousand hits, so paging with a cursor cannot reach further
    // matches; the only way in is for the match set itself to shrink, which this batch's write just did. Tagging
    // leaves behind the documents still missing at least one of the tags; untagging leaves behind the ones still
    // carrying at least one. The caller sends this back as the next query, so wrapping it again each round would
    // grow the query without bound, a hundred clauses a round with a hundred tags; the filter is idempotent, so a
    // query already carrying it is returned untouched.
    static String continuation(String query, List<String> tags, boolean tagging) {
        String joiner = tagging ? " AND " : " OR ";
        String tagClauses = tags.stream().map(McpTagByQuery::phrase).collect(Collectors.joining(joiner));
        String filter = (tagging ? " AND NOT (%s)" : " AND (%s)").formatted(tagClauses);
        return query.endsWith(filter) ? query : "(%s)%s".formatted(query, filter);
    }

    // A tag becomes a query_string phrase, so its own backslashes and double quotes are escaped first or they
    // would close the phrase early or fail to parse.
    private static String phrase(String tag) {
        String escaped = tag.replace("\\", "\\\\").replace("\"", "\\\"");
        return "tags:\"" + escaped + "\"";
    }

    // The batch filling its requested size is necessary but not sufficient: an exact count (relation "eq") that
    // equals what was just written proves nothing is left, however full the batch looks. Only a lower-bound count,
    // or an exact count that still exceeds what was written, leaves work provably outstanding.
    private static boolean more(Batch resolved, int size) {
        return resolved.ids().size() == size && (resolved.lowerBound() || resolved.matched() > resolved.ids().size());
    }

    // The response to a confirmed batch: what got written, whether more is provably outstanding, and, when it is,
    // the query to send next. The caller must see each batch's count before the next runs, so the continuation
    // query rides along rather than being left for the model to compose itself.
    static Map<String, Object> written(String action, McpTagDocumentsTool.BatchTag batch, Batch resolved, int size,
                                       String refreshCaveat) {
        boolean tagging = "tag".equals(action);
        boolean more = more(resolved, size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", batch.project());
        result.put(tagging ? "tagged" : "untagged", resolved.ids().size());
        result.put("tags", batch.tags());
        result.put("more", more);
        if (more) {
            result.put("next_query", continuation(batch.query(), batch.tags(), tagging));
        }
        result.put("message", writtenMessage(action, batch, resolved, more, refreshCaveat));
        return result;
    }

    private static String writtenMessage(String action, McpTagDocumentsTool.BatchTag batch, Batch resolved,
                                         boolean more, String refreshCaveat) {
        if (resolved.ids().isEmpty()) {
            return "No documents matched the query in project %s, so there is nothing to %s.".formatted(batch.project(),
                                                                                                        action);
        }
        String verb = "tag".equals(action) ? "Tagged" : "Untagged";
        StringBuilder text = new StringBuilder(
                "%s %d documents in project %s with %s".formatted(verb, resolved.ids().size(), batch.project(),
                                                                  batch.tags()));
        if (more) {
            // A lower-bound count only proves more remain, never how many; an exact count, past what was just
            // written, says exactly how many, since track_total_hits was not asked to pay for more precision.
            String remaining = resolved.lowerBound() ? "more documents remain" :
                               "%d more remain".formatted(resolved.matched() - resolved.ids().size());
            text.append(", %s; call %s_documents again with next_query and no confirmed flag to continue".formatted(
                    remaining, action));
        }
        text.append('.');
        if (refreshCaveat != null) {
            text.append(' ').append(refreshCaveat);
        }
        return text.toString();
    }

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
            return "No documents matched the query in project %s, so there is nothing to %s.".formatted(batch.project(),
                                                                                                        action);
        }
        String count = (resolved.lowerBound() ? "at least " : "") + resolved.matched();
        // One confirmed call writes one batch, not the whole match set, so the count alone would promise more than
        // the next call delivers whenever the batch is the smaller of the two.
        int size = batchSize(batch.tags().size(), "tag".equals(action));
        String reach = resolved.lowerBound() || resolved.matched() > size ?
                       "The next call %ss the first %d of them, and says whether more remain.".formatted(action, size) :
                       "The next call %ss all of them.".formatted(action);
        return ("About to %s %s documents in project %s with %s. %s Show this count to the user and ask whether to " +
                "proceed. If they agree, call %s_documents again with the same arguments and confirmed: true. " +
                "Nothing has been changed yet.").formatted(action, count, batch.project(), batch.tags(), reach, action);
    }
}
