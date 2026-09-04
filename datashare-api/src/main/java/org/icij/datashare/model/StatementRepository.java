package org.icij.datashare.model;

import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/** Persists statements. A save is chunked into independent transactions: a partial failure leaves a
 *  partial, re-runnable result rather than an all-or-nothing outcome, which the upsert's idempotence
 *  makes safe. */
public interface StatementRepository {
    /** Upserts by statement id. A row the run re-observed unchanged is left exactly as it was, not
     *  even its written-at moves, so a no-op re-run writes nothing. A row whose content moved (an
     *  ontology bump, a re-recorded original value) is refreshed, run and timestamp included. The
     *  statements are consumed lazily, one chunk at a time, so a whole extraction never has to fit in memory; the
     *  stream is closed on return. Returns the number of statements written, or fewer when the JDBC
     *  driver rewrites the batch and reports no per-row count. */
    int save(String projectId, String runId, Stream<Statement> statements);

    /** Every entity of a project, rebuilt by grouping its statements. The stream is read lazily from
     *  a pooled connection and closed once {@code consumer} returns or throws, so {@code consumer}
     *  has to consume it: returning the stream itself hands back a closed one. */
    <R> R entities(String projectId, Function<Stream<ModelEntity>, R> consumer);

    /** The entity a project holds under this id. An id shared by two models yields the first model in
     *  natural order, since an entity belongs to one model, and the entity names the model it came
     *  from. */
    Optional<ModelEntity> entity(String projectId, String entityId);
}
