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

    Optional<ExtractionMapping> get(String id);

    List<ExtractionMapping> list(String projectId);

    boolean delete(String id);
}
