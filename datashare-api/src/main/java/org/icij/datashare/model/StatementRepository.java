package org.icij.datashare.model;

import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/** Persists statements. A save is chunked into independent transactions: a partial failure leaves a
 *  partial, re-runnable result rather than an all-or-nothing outcome, which the upsert's idempotence
 *  makes safe. */
public interface StatementRepository {
    /** What a {@link #replace} did. The two counts stay apart because a retraction that matched
     *  nothing is how a sheet key that does not name what the write stored shows itself, and because
     *  a stream that yields nothing retracts everything and writes zero. */
    record Replaced(int retracted, int written) {}

    /** Upserts by statement id. A row the run re-observed unchanged is left exactly as it was, not
     *  even its written-at moves: a no-op re-run writes nothing. A row whose content moved (an
     *  ontology bump, a re-recorded original value) is refreshed, run and timestamp included. The
     *  statements are consumed lazily, one chunk at a time, so a whole extraction never has to fit in
     *  memory; the stream is closed on return. Returns the number of statements written, or fewer
     *  when the JDBC driver rewrites the batch and reports no per-row count. */
    int save(String projectId, String runId, Stream<Statement> statements);

    /** Every entity of a project, rebuilt by grouping its statements. The stream is read lazily from
     *  a pooled connection and closed once {@code consumer} returns or throws, so {@code consumer}
     *  has to consume it: returning the stream itself hands back a closed one. */
    <R> R entities(String projectId, Function<Stream<ModelEntity>, R> consumer);

    /** Deletes every statement one sheet of a document contributed to a project, and returns how
     *  many. A null sheet names the empty sheet a single-table format writes, as it does on the way
     *  in. Changing which sheet a mapping reads still strands what it wrote under the old one, since
     *  nothing then retracts it. */
    int deleteBySheet(String projectId, String documentId, String sheet);

    /** Rewrites what one sheet of a document contributed. The retraction rides the first chunk's
     *  transaction, so an extraction that fails on its first row leaves the sheet as it was rather
     *  than empty; later chunks commit on their own, so a failure part way through does leave the
     *  sheet half rewritten. Refuses a statement whose provenance is not the document and sheet being
     *  replaced, since the delete keys on the arguments and the rows key on themselves. The rows
     *  written here are new, so the conditional upsert a plain save leans on cannot spare any. */
    Replaced replace(String projectId, String runId, String documentId, String sheet, Stream<Statement> statements);

    /** The entity a project holds under this id. An id shared by two models yields the first model in
     *  natural order, since an entity belongs to one model, and the entity names the model it came
     *  from. */
    Optional<ModelEntity> entity(String projectId, String entityId);
}
