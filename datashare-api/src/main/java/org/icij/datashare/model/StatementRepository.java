package org.icij.datashare.model;

import java.util.Collection;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/** Persists statements. A save is chunked into independent transactions: a partial failure leaves a
 *  partial, re-runnable result rather than an all-or-nothing outcome, which the upsert's idempotence
 *  makes safe. */
public interface StatementRepository {
    /** Upserts by statement id. An existing row is left untouched: the write is a no-op on a re-save.
     *  Returns the number of statements inserted, or fewer when the JDBC driver rewrites the batch
     *  and reports no per-row count. */
    int save(String projectId, String runId, Collection<Statement> statements);

    /** Every entity of a project, rebuilt by grouping its statements. The stream is read lazily from
     *  a pooled connection and closed once {@code consumer} returns or throws, so {@code consumer}
     *  has to consume it: returning the stream itself hands back a closed one. */
    <R> R entities(String projectId, Function<Stream<ModelEntity>, R> consumer);

    /** The entity a project holds under this id. An id shared by two models yields the first by model
     *  order, since an entity belongs to one model. */
    Optional<ModelEntity> entity(String projectId, String entityId);
}
