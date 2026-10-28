package org.icij.datashare.web;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
import org.icij.datashare.web.errors.BadRequestException;
import org.icij.datashare.web.errors.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger logger = LoggerFactory.getLogger(McpTagDocumentsTool.class);
    private final DocumentResource documentResource;
    private final McpTagByQuery tagByQuery;

    @Inject
    public McpTagDocumentsTool(DocumentResource documentResource, McpTagByQuery tagByQuery) {
        this.documentResource = documentResource;
        this.tagByQuery = tagByQuery;
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
               "document ids, duplicates and unknown ids included, not of documents actually changed. A query " +
               "selects the documents instead of doc_ids; a query call only reports what it would do and writes " +
               "nothing until called again with confirmed: true.";
    }

    @Override
    public Object call(JsonNode arguments, Context context) throws IOException {
        BatchTag batch = BatchTag.from(arguments);
        if (batch.query() != null) {
            if (!batch.confirmed()) {
                return McpTagByQuery.confirmation("tag", batch,
                                                  tagByQuery.resolve(batch.project(), batch.query(), 0, context));
            }
            return writeQueryBatch(batch, context);
        }
        batch.requireWithinWriteBudget();
        try {
            documentResource.groupTagDocument(batch.project(), batch.writeFor(batch.docIds()), context);
        } catch (ForbiddenException e) {
            throw new McpToolException.Forbidden(batch.project(), e);
        } catch (BadRequestException e) {
            throw McpToolException.InvalidArgument.because(e.getMessage());
        } catch (IOException | ElasticsearchException e) {
            // DocumentResource writes the index before the database and only the indexer raises these two, the
            // database raising a DataAccessException instead, so the database has nothing. The index may have
            // changed part of the batch before failing, since groupTagUntag accumulates across its retries, hence
            // "may" rather than a promise. Both sides ignore what is already in the wanted state, so a retry
            // converges.
            throw new McpToolException(
                    "the search index refused the write, so nothing was saved in the database; part of the batch " +
                    "may already be tagged in the index, which a retry of this call will reconcile", e);
        }
        return Map.of("project", batch.project(), "tagged", batch.docIds().size(), "tags", batch.tags());
    }

    // Sized to the write budget rather than the id cap, since this batch, unlike doc_ids, multiplies by the tag
    // count: fewer ids per batch keeps the row count under MAX_TAG_WRITES.
    private Object writeQueryBatch(BatchTag batch, Context context) throws IOException {
        int size = McpTagByQuery.batchSize(batch.tags().size(), true);
        McpTagByQuery.Batch resolved = tagByQuery.resolve(batch.project(), batch.query(), size, context);
        String refreshCaveat = null;
        if (!resolved.ids().isEmpty()) {
            try {
                documentResource.groupTagDocument(batch.project(), batch.writeFor(resolved.ids()), context);
            } catch (ForbiddenException e) {
                throw new McpToolException.Forbidden(batch.project(), e);
            } catch (BadRequestException e) {
                throw McpToolException.InvalidArgument.because(e.getMessage());
            } catch (IOException | ElasticsearchException e) {
                throw new McpToolException(
                        "the search index refused the write, so nothing was saved in the database; part of the " +
                        "batch may already be tagged in the index, which a retry of this call will reconcile", e);
            }
            refreshCaveat = refreshAfterWrite(batch.project());
        }
        return McpTagByQuery.written("tag", batch, resolved, size, refreshCaveat);
    }

    // A refresh failure does not undo a write that already succeeded; it only means the continuation query below
    // may still see what this batch just tagged, which the message flags as a caveat rather than failing the call.
    private String refreshAfterWrite(String project) {
        try {
            tagByQuery.refresh(project);
            return null;
        } catch (IOException e) {
            logger.warn("failed to refresh index {} after a confirmed tag-by-query write", project, e);
            return "The index refresh failed, so next_query may not be accurate yet; retrying it shortly should " +
                   "catch up.";
        }
    }

    /** The arguments both tagging tools take: either an explicit id list or a query, never both. */
    record BatchTag(String project, List<String> docIds, String query, List<String> tags, boolean confirmed) {
        static BatchTag from(JsonNode arguments) {
            String project = McpArguments.requireText(arguments, "project");
            List<String> tags = McpArguments.requireTexts(arguments, "tags", MAX_TAGS);
            boolean hasQuery = arguments.hasNonNull("query");
            boolean hasDocIds = arguments.hasNonNull("doc_ids");
            if (hasQuery == hasDocIds) {
                throw McpToolException.InvalidArgument.because(
                        "exactly one of \"doc_ids\" and \"query\" is required, not both and not neither");
            }
            String query = hasQuery ? McpArguments.optionalText(arguments, "query") : null;
            List<String> docIds = hasDocIds ? McpArguments.requireTexts(arguments, "doc_ids", MAX_DOC_IDS) : null;
            boolean confirmed = arguments.path("confirmed").asBoolean(false);
            return new BatchTag(project, docIds, query, tags, confirmed);
        }

        /** Only tagging writes a row per pair; untagging deletes with two IN lists and does not multiply. The
         * query path has no ids yet when this runs, so it has nothing to check here. */
        BatchTag requireWithinWriteBudget() {
            if (docIds == null) {
                return this;
            }
            long writes = (long) docIds.size() * tags.size();
            if (writes > MAX_TAG_WRITES) {
                throw McpToolException.InvalidArgument.because(
                        "tagging %d documents with %d tags writes %d rows, more than the %d allowed in one call".formatted(
                                docIds.size(), tags.size(), writes, MAX_TAG_WRITES));
            }
            return this;
        }

        /** Builds the write for an id list, whether it is the explicit doc_ids or one a query resolved. */
        DocumentResource.BatchTagQuery writeFor(List<String> ids) {
            return new DocumentResource.BatchTagQuery(tags, ids);
        }
    }
}
