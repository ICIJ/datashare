package org.icij.datashare.model;

import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/** Persists statements. A save is chunked into independent transactions: a partial failure leaves a
 *  partial, re-runnable result rather than an all-or-nothing outcome, which the upsert's idempotence
 *  makes safe. */
public interface StatementRepository {
    /** Upserts by statement id. A row the run re-observed unchanged is left exactly as it was, not
     *  even its written-at moves: a no-op re-run writes nothing, and staleness is handled by deleting
     *  a sheet's statements before rewriting them, not by per-row timestamps. A row whose content
     *  moved (an ontology bump, a re-recorded original value) is refreshed, run and timestamp
     *  included. The statements are consumed lazily, one chunk at a time, so a whole extraction
     *  never has to fit in memory; the stream is closed on return. Returns the number of statements
     *  written, or fewer when the JDBC driver rewrites the batch and reports no per-row count. */
    int save(String projectId, String runId, Stream<Statement> statements);

    /** Every entity of a project, rebuilt by grouping its statements. The stream is read lazily from
     *  a pooled connection and closed once {@code consumer} returns or throws, so {@code consumer}
     *  has to consume it: returning the stream itself hands back a closed one. */
    <R> R entities(String projectId, Function<Stream<ModelEntity>, R> consumer);

    /** Deletes every statement a document contributed to a project, and returns how many. This is
     *  the retraction path: re-extracting a document deletes its statements first, so a corrected
     *  value, a re-typed mapping or a shrunken file leaves no stale row behind. Document scope, not
     *  run scope: one entity legitimately aggregates statements from several documents, and only the
     *  document being re-read is stale. */
    int deleteByDocument(String projectId, String documentId);

    /** Rewrites what a document contributed: the retraction and the write, in the one order that
     *  leaves no stale row behind, since doing it the other way round deletes what it has just
     *  written. Not atomic, because a save commits per chunk: a crash between the two leaves the
     *  document retracted and re-extractable rather than half rewritten, which is the safe side of
     *  the window to fall on. */
    default int replace(String projectId, String runId, String documentId, Stream<Statement> statements) {
        deleteByDocument(projectId, documentId);
        return save(projectId, runId, statements);
    }

    /** The entity a project holds under this id. An id shared by two models yields the first model in
     *  natural order, since an entity belongs to one model, and the entity names the model it came
     *  from. */
    Optional<ModelEntity> entity(String projectId, String entityId);
}
