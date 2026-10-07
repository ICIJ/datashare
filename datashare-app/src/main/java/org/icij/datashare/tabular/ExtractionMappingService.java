package org.icij.datashare.tabular;

/** Saves extraction mappings for the CLI and the REST API, so both entry points apply the same rules. */
public interface ExtractionMappingService {
    /**
     * @throws DuplicateExtractionMapping when the project already holds the mapping's id: a mapping is
     *         immutable, so a change is a new mapping with a new id.
     * @throws InvalidExtractionMapping when the mapping does not validate against its target model.
     */
    void save(ExtractionMapping mapping) throws DuplicateExtractionMapping;

    /** Idempotent counterpart of {@link #save}: the very mapping stored under its id is not a conflict, so a
     *  run that failed after its save can be retried with the same input. */
    void saveIfIdentical(ExtractionMapping mapping) throws DuplicateExtractionMapping;
}
