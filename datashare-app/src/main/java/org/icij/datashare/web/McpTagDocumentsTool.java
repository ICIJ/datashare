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
            McpTagByQuery.requireTagsWritable(batch.tags());
            if (!batch.confirmed()) {
                return McpTagByQuery.confirmation("tag", batch,
                                                  tagByQuery.resolve(batch.project(), batch.query(), 0, context));
            }
            return tagByQuery.writeBatch("tag", batch, context, this::tagOrFail);
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

    // The write of one query batch, with the failure worded for query mode: unlike doc_ids, repeating the call
    // re-resolves the query, and the index having gone first means the documents it already tagged no longer match
    // it, so they would stay missing from the database.
    private void tagOrFail(String project, DocumentResource.BatchTagQuery query, Context context) throws IOException {
        try {
            documentResource.groupTagDocument(project, query, context);
        } catch (ForbiddenException e) {
            throw new McpToolException.Forbidden(project, e);
        } catch (BadRequestException e) {
            throw McpToolException.InvalidArgument.because(e.getMessage());
        } catch (IOException | ElasticsearchException e) {
            throw new McpToolException(
                    "the search index refused the write, so nothing was saved in the database; part of the batch " +
                    "may already be tagged in the index, and those documents no longer match the query, so " +
                    "repeating this call will not bring them back; tag them by doc_ids to reconcile", e);
        }
    }

    /** The arguments both tagging tools take: either an explicit id list or a query, never both. */
    record BatchTag(String project, List<String> docIds, String query, List<String> tags, boolean confirmed) {
        static BatchTag from(JsonNode arguments) {
            // The same single-index guard search_documents keeps: a comma-separated list would otherwise be
            // counted across both indices on the first pass and refused by the grant check on the second.
            String project = McpIndexSearch.requireSingleProject(arguments);
            List<String> tags = McpArguments.requireTexts(arguments, "tags", MAX_TAGS);
            boolean hasQuery = arguments.hasNonNull("query");
            boolean hasDocIds = arguments.hasNonNull("doc_ids");
            if (hasQuery == hasDocIds) {
                throw McpToolException.InvalidArgument.because(
                        "exactly one of \"doc_ids\" and \"query\" is required, not both and not neither");
            }
            String query = hasQuery ? McpArguments.requireText(arguments, "query") : null;
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
