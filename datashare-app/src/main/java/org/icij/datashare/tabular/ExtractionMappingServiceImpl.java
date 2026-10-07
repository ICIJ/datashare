package org.icij.datashare.tabular;

import com.google.inject.Inject;

public class ExtractionMappingServiceImpl implements ExtractionMappingService {
    private final ExtractionMappingRepository mappings;

    @Inject
    public ExtractionMappingServiceImpl(ExtractionMappingRepository mappings) {
        this.mappings = mappings;
    }

    @Override
    public void save(ExtractionMapping mapping) throws DuplicateExtractionMapping {
        if (!mappings.save(mapping)) {
            throw new DuplicateExtractionMapping(mapping.projectId(), mapping.id());
        }
    }

    @Override
    public void saveIfIdentical(ExtractionMapping mapping) throws DuplicateExtractionMapping {
        if (!mappings.save(mapping) && !isStored(mapping)) {
            throw new DuplicateExtractionMapping(mapping.projectId(), mapping.id());
        }
    }

    // A stored definition that no longer reads cannot be the caller's, so it stays a conflict.
    private boolean isStored(ExtractionMapping mapping) {
        try {
            return mappings.get(mapping.projectId(), mapping.id()).filter(mapping::equals).isPresent();
        } catch (UnreadableExtractionMapping e) {
            return false;
        }
    }
}
