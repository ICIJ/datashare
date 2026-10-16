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
    // The widths of document_tag.doc_id and document_tag.label in 002-tag-document.yml. Checked here so an
    // over-long value is a named invalid argument, instead of a Postgres error whose message hands the client
    // the INSERT statement.
    static final int MAX_DOC_ID_LENGTH = 96;
    static final int MAX_TAG_LENGTH = 64;
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
               + "user. Tag matching is case-sensitive against the stored label, so \"invoice\" and \"Invoice\" are "
               + "different tags. The returned count is just the number of requested document ids (duplicates and "
               + "non-existent ids included), not a count of documents actually changed.";
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
                                McpArguments.requireTexts(arguments, "doc_ids", MAX_ITEMS, MAX_DOC_ID_LENGTH),
                                McpArguments.requireTexts(arguments, "tags", MAX_ITEMS, MAX_TAG_LENGTH));
        }

        DocumentResource.BatchTagQuery query() {
            return new DocumentResource.BatchTagQuery(tags, docIds);
        }
    }
}
