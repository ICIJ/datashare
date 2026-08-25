package org.icij.datashare.tabular;

import java.util.List;
import java.util.Optional;

/** Persists extraction mappings. Implementations own atomicity, as {@code ManifestRepository} does. */
public interface ExtractionMappingRepository {
    /** Saves a new mapping. Returns false when the id already exists: a mapping is immutable, so a
     *  change is a new mapping with a new id, and a run's statements always resolve back to the
     *  mapping that produced them.
     *  @throws InvalidExtractionMapping when the mapping does not validate against its target model. */
    boolean save(ExtractionMapping mapping);

    /** @throws UnreadableExtractionMapping when the stored row exists but its definition no longer
     *  parses, e.g. against a model the mapping's own type or property has since dropped. */
    Optional<ExtractionMapping> get(String projectId, String id);

    /** Skips a row it cannot read rather than failing the whole list. */
    List<ExtractionMapping> list(String projectId);

    boolean delete(String projectId, String id);
}
