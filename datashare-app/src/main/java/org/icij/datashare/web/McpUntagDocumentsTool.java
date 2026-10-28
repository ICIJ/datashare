package org.icij.datashare.web;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
import org.icij.datashare.web.errors.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.Map;

public class McpUntagDocumentsTool implements McpTool {
    private static final Logger logger = LoggerFactory.getLogger(McpUntagDocumentsTool.class);
    private final DocumentResource documentResource;
    private final McpTagByQuery tagByQuery;

    @Inject
    public McpUntagDocumentsTool(DocumentResource documentResource, McpTagByQuery tagByQuery) {
        this.documentResource = documentResource;
        this.tagByQuery = tagByQuery;
    }

    @Override
    public String name() {
        return "untag_documents";
    }

    @Override
    public String description() {
        return "Removes up to 100 tags from up to 10000 documents at once. A tag the document does not carry is " +
               "ignored. Tag matching is case-sensitive against the stored label, so \"invoice\" will not remove " +
               "a tag stored as \"Invoice\". The returned count is the number of requested document ids, " +
               "duplicates and unknown ids included, not of documents actually changed. A query selects the " +
               "documents instead of doc_ids; a query call only reports what it would do and writes nothing " +
               "until called again with confirmed: true.";
    }

    @Override
    public Object call(JsonNode arguments, Context context) throws IOException {
        McpTagDocumentsTool.BatchTag batch = McpTagDocumentsTool.BatchTag.from(arguments);
        if (batch.query() != null) {
            if (!batch.confirmed()) {
                return McpTagByQuery.confirmation("untag", batch,
                        tagByQuery.resolve(batch.project(), batch.query(), 0, context));
            }
            return writeQueryBatch(batch, context);
        }
        try {
            documentResource.groupUntagDocument(batch.project(), batch.writeFor(batch.docIds()), context);
        } catch (ForbiddenException e) {
            throw new McpToolException.Forbidden(batch.project(), e);
        } catch (IOException | ElasticsearchException e) {
            // Same as in McpTagDocumentsTool: the index goes first, so the database has nothing, and the index
            // may have changed part of the batch before failing.
            throw new McpToolException(
                    "the search index refused the write, so nothing was removed from the database; part of the " +
                    "batch may already be untagged in the index, which a retry of this call will reconcile", e);
        }
        return Map.of("project", batch.project(), "untagged", batch.docIds().size(), "tags", batch.tags());
    }

    // Untagging is not capped by the write budget, so its batch is sized to the id cap alone.
    private Object writeQueryBatch(McpTagDocumentsTool.BatchTag batch, Context context) throws IOException {
        int size = McpTagByQuery.batchSize(batch.tags().size(), false);
        McpTagByQuery.Batch resolved = tagByQuery.resolve(batch.project(), batch.query(), size, context);
        boolean more = resolved.ids().size() == size;
        String refreshCaveat = null;
        if (!resolved.ids().isEmpty()) {
            try {
                documentResource.groupUntagDocument(batch.project(), batch.writeFor(resolved.ids()), context);
            } catch (ForbiddenException e) {
                throw new McpToolException.Forbidden(batch.project(), e);
            } catch (IOException | ElasticsearchException e) {
                throw new McpToolException(
                        "the search index refused the write, so nothing was removed from the database; part of " +
                        "the batch may already be untagged in the index, which a retry of this call will "
                        + "reconcile", e);
            }
            refreshCaveat = refreshAfterWrite(batch.project());
        }
        return McpTagByQuery.written("untag", batch, resolved, more, refreshCaveat);
    }

    // A refresh failure does not undo a write that already succeeded; it only means the continuation query below
    // may still see what this batch just untagged, which the message flags as a caveat rather than failing the
    // call.
    private String refreshAfterWrite(String project) {
        try {
            tagByQuery.refresh(project);
            return null;
        } catch (IOException e) {
            logger.warn("failed to refresh index {} after a confirmed untag-by-query write", project, e);
            return "The index refresh failed, so next_query may not be accurate yet; retrying it shortly should "
                   + "catch up.";
        }
    }
}
