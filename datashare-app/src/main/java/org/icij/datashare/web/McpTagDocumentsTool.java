package org.icij.datashare.web;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
import org.icij.datashare.web.errors.BadRequestException;
import org.icij.datashare.web.errors.ForbiddenException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class McpTagDocumentsTool implements McpTool {
    // The rails on a write with no undo beyond the agent calling untag_documents itself. Elasticsearch rejects a
    // terms query above index.max_terms_count, 65536 by default, and groupTagUntag selects the documents with one,
    // so the id cap stays well under it.
    static final int MAX_DOC_IDS = 10_000;
    static final int MAX_TAGS = 100;
    // What bounds the work is the product, since the insert writes one row per pair. Measured against PostgreSQL:
    // 100000 rows take under 4 seconds, 1000000 take 29, long enough for a client to give up while the
    // transaction is still open.
    static final int MAX_TAG_WRITES = 100_000;
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
        return "Adds up to 100 tags to up to 10000 documents at once, for at most 100000 document-tag pairs per " +
               "call, attributed to the calling user. Tag matching is case-sensitive against the stored label, " +
               "so \"invoice\" and \"Invoice\" are different tags. The returned count is the number of requested " +
               "document ids, duplicates and unknown ids included, not of documents actually changed.";
    }

    @Override
    public Object call(JsonNode arguments, Context context) throws IOException {
        BatchTag batch = BatchTag.from(arguments).requireWithinWriteBudget();
        try {
            documentResource.groupTagDocument(batch.project, batch.query(), context);
        } catch (ForbiddenException e) {
            throw new McpToolException.Forbidden(batch.project, e);
        } catch (BadRequestException e) {
            throw McpToolException.InvalidArgument.because(e.getMessage());
        } catch (IOException | ElasticsearchException e) {
            // The database write runs first and fails as a DataAccessException, so neither of these can come from
            // it: reaching here means the tags are saved and the index is behind. ElasticsearchException is a
            // RuntimeException, which is how index_not_found and a failing script arrive.
            throw new McpToolException(
                    "the tags were saved but the search index was not updated, so they will not match a search " +
                    "until these documents are reindexed", e);
        }
        return Map.of("project", batch.project, "tagged", batch.docIds.size(), "tags", batch.tags);
    }

    /** The arguments both tagging tools take. */
    record BatchTag(String project, List<String> docIds, List<String> tags) {
        static BatchTag from(JsonNode arguments) {
            return new BatchTag(McpArguments.requireText(arguments, "project"),
                                McpArguments.requireTexts(arguments, "doc_ids", MAX_DOC_IDS),
                                McpArguments.requireTexts(arguments, "tags", MAX_TAGS));
        }

        /** Only tagging writes a row per pair; untagging deletes with two IN lists and does not multiply. */
        BatchTag requireWithinWriteBudget() {
            long writes = (long) docIds.size() * tags.size();
            if (writes > MAX_TAG_WRITES) {
                throw McpToolException.InvalidArgument.because(
                        "tagging %d documents with %d tags writes %d rows, more than the %d allowed in one call".formatted(
                                docIds.size(), tags.size(), writes, MAX_TAG_WRITES));
            }
            return this;
        }

        DocumentResource.BatchTagQuery query() {
            return new DocumentResource.BatchTagQuery(tags, docIds);
        }
    }
}
