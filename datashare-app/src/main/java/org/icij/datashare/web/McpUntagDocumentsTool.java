package org.icij.datashare.web;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
import org.icij.datashare.web.errors.ForbiddenException;
import java.io.IOException;
import java.util.Map;

public class McpUntagDocumentsTool implements McpTool {
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
            // Task 3 does the confirmed write; until then a query call always only counts, whatever confirmed is.
            return McpTagByQuery.confirmation("untag", batch, tagByQuery.resolve(batch.project(), batch.query(), 0,
                                                                                  context));
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
}
