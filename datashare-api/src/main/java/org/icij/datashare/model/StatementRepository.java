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
     *  Returns the number of statements actually inserted. */
    int save(String projectId, String runId, Collection<Statement> statements);

    /** Every entity of a project, rebuilt by grouping its statements. Lazily read, and backed by a
     *  pooled connection that is released only when the returned stream is closed. The caller MUST
     *  close it, including on every early exit (e.g. {@code findFirst}, {@code limit},
     *  {@code anyMatch}) or exception: abandoning it without closing leaks a pooled connection.
     *  Prefer {@link #entities(String, Function)}, which closes it for you. */
    Stream<ModelEntity> entities(String projectId);

    /** Same as {@link #entities(String)}, but closes the stream for the caller once {@code consumer}
     *  returns or throws. */
    <R> R entities(String projectId, Function<Stream<ModelEntity>, R> consumer);

    Optional<ModelEntity> entity(String projectId, String entityId);
}
