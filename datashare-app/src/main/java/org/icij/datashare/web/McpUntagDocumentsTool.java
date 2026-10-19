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

    @Inject
    public McpUntagDocumentsTool(DocumentResource documentResource) {
        this.documentResource = documentResource;
    }

    @Override
    public String name() {
        return "untag_documents";
    }

    @Override
    public String description() {
        return "Removes up to 100 tags from up to 100 documents at once. A tag the document does not carry is "
               + "ignored. Tag matching is case-sensitive against the stored label, so \"invoice\" will not remove "
               + "a tag stored as \"Invoice\". The returned count is the number of requested document ids, "
               + "duplicates and unknown ids included, not of documents actually changed.";
    }

    @Override
    public Object call(JsonNode arguments, Context context) throws IOException {
        McpTagDocumentsTool.BatchTag batch = McpTagDocumentsTool.BatchTag.from(arguments);
        try {
            documentResource.groupUntagDocument(batch.project(), batch.query(), context);
        } catch (ForbiddenException e) {
            throw new McpToolException.Forbidden(batch.project(), e);
        } catch (IOException | ElasticsearchException e) {
            // Same asymmetry as in McpTagDocumentsTool: the database delete has happened, the index has not.
            throw new McpToolException(
                    "the tags were removed but the search index was not updated, so they will still match a "
                    + "search until these documents are reindexed", e);
        }
        return Map.of("project", batch.project(), "untagged", batch.docIds().size(), "tags", batch.tags());
    }
}
