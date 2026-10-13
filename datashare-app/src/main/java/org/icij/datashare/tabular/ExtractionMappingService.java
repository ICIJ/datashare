package org.icij.datashare.tabular;

import org.icij.datashare.user.User;
import java.util.Optional;

/** Saves extraction mappings for the CLI and the REST API, so both entry points apply the same rules. */
public interface ExtractionMappingService {
    /**
     * @throws DuplicateExtractionMapping when the project already holds the mapping's id: a mapping is
     *         immutable, so a change is a new mapping with a new id.
     * @throws InvalidExtractionMapping when the mapping does not validate against its target model.
     */
    void save(ExtractionMapping mapping) throws DuplicateExtractionMapping;

    /** Idempotent counterpart of {@link #save}: the very mapping stored under its id is not a conflict, so a
     *  run that failed after its save can be retried with the same input.
     *  @return false when that very mapping was already stored, so the caller can say nothing new was saved. */
    boolean saveIfIdentical(ExtractionMapping mapping) throws DuplicateExtractionMapping;

    /**
     * Reads a mapping for its author holding the member role, or for a project admin. Anyone else is
     * refused before learning whether the id exists or still parses, so the ids cannot be probed.
     * @throws ForbiddenExtractionMapping when the requester may not use the mapping.
     * @throws UnreadableExtractionMapping when the stored definition no longer parses.
     */
    Optional<ExtractionMapping> get(String projectId, String id, User requester);
}
