package org.icij.datashare.model;

import java.util.Collection;
import java.util.Optional;
import java.util.stream.Stream;

/** Persists statements. Implementations own atomicity, as {@code ManifestRepository} does. */
public interface StatementRepository {
    /** Upserts by statement id. An existing row keeps its first_seen and takes the new last_seen.
     *  Returns the number of statements written. */
    int save(String projectId, String runId, Collection<Statement> statements);

    /** Every entity of a project, rebuilt by grouping its statements. Lazily read: the caller closes it. */
    Stream<ModelEntity> entities(String projectId);

    Optional<ModelEntity> entity(String projectId, String entityId);
}
