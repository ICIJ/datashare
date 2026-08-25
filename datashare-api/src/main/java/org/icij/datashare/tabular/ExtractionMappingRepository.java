package org.icij.datashare.tabular;

import java.util.List;
import java.util.Optional;

/** Persists extraction mappings. Implementations own atomicity, as {@code ManifestRepository} does. */
public interface ExtractionMappingRepository {
    /** Saves a new mapping. Returns false when the project already holds that id: a mapping is
     *  immutable, so a change is a new mapping with a new id. Ids are project-scoped, so two projects
     *  can each hold one under the same id.
     *  @throws InvalidExtractionMapping when the mapping does not validate against its target model. */
    boolean save(ExtractionMapping mapping);

    /** @throws UnreadableExtractionMapping when the stored row exists but its definition no longer
     *  parses, e.g. against a model the registry no longer knows. A type or property the model has
     *  since dropped still parses: {@link ExtractionMapping#validate()} runs on save, not on read. */
    Optional<ExtractionMapping> get(String projectId, String id);

    /** Skips a row it cannot read rather than failing the whole list. */
    List<ExtractionMapping> list(String projectId);

    boolean delete(String projectId, String id);
}
