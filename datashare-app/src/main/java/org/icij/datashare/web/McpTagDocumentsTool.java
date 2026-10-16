package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
import org.icij.datashare.web.errors.ForbiddenException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class McpTagDocumentsTool implements McpTool {
    /** The safety rail on a write with no undo beyond the agent calling untag_documents itself. */
    static final int MAX_ITEMS = 100;
    private final DocumentResource documentResource;

    @Inject
    public McpTagDocumentsTool(DocumentResource documentResource) {
        this.documentResource = documentResource;
    }

    @Override
    public String name() {
        return "tag_documents";
    }

    @Override
    public String description() {
        return "Adds tags to up to 100 documents at once, by document id. The tags are attributed to the calling "
               + "user. An id that is not in the project is tagged anyway and still counted, so the count is not a "
               + "proof that the documents exist.";
    }

    @Override
    public Object call(JsonNode arguments, Context context) throws IOException {
        BatchTag batch = BatchTag.from(arguments);
        try {
            documentResource.groupTagDocument(batch.project, batch.query(), context);
        } catch (ForbiddenException e) {
            throw new McpToolException.Forbidden(batch.project, e);
        }
        return Map.of("project", batch.project, "tagged", batch.docIds.size(), "tags", batch.tags);
    }

    /** The arguments both tagging tools take. */
    record BatchTag(String project, List<String> docIds, List<String> tags) {
        static BatchTag from(JsonNode arguments) {
            return new BatchTag(McpArguments.requireText(arguments, "project"),
                                McpArguments.requireTexts(arguments, "doc_ids", MAX_ITEMS),
                                McpArguments.requireTexts(arguments, "tags", MAX_ITEMS));
        }

        DocumentResource.BatchTagQuery query() {
            return new DocumentResource.BatchTagQuery(tags, docIds);
        }
    }
}
