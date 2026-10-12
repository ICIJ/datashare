package org.icij.datashare.tabular;

import com.google.inject.Inject;
import org.icij.datashare.tabular.DuplicateExtractionMapping.Reason;

public class ExtractionMappingServiceImpl implements ExtractionMappingService {
    private final ExtractionMappingRepository mappings;

    @Inject
    public ExtractionMappingServiceImpl(ExtractionMappingRepository mappings) {
        this.mappings = mappings;
    }

    @Override
    public void save(ExtractionMapping mapping) throws DuplicateExtractionMapping {
        if (!mappings.save(mapping)) {
            throw new DuplicateExtractionMapping(mapping.projectId(), mapping.id(), Reason.ID_TAKEN);
        }
    }

    @Override
    public boolean saveIfIdentical(ExtractionMapping mapping) throws DuplicateExtractionMapping {
        if (mappings.save(mapping)) {
            return true;
        }
        if (!isStored(mapping)) {
            throw new DuplicateExtractionMapping(mapping.projectId(), mapping.id(), Reason.DIFFERENT_DEFINITION);
        }
        return false;
    }

    // A stored definition that no longer reads cannot be the caller's, so it stays a conflict.
    private boolean isStored(ExtractionMapping mapping) throws DuplicateExtractionMapping {
        try {
            return mappings.get(mapping.projectId(), mapping.id()).filter(mapping::equals).isPresent();
        } catch (UnreadableExtractionMapping e) {
            throw new DuplicateExtractionMapping(mapping.projectId(), mapping.id(), Reason.UNREADABLE_DEFINITION);
        }
    }
}
