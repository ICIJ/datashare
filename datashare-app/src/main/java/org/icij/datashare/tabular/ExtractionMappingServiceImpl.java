package org.icij.datashare.tabular;

import com.google.inject.Inject;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.tasks.StructuredEntityExtractionTask;
import org.icij.datashare.user.User;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.tasks.StructuredEntityExtractionTask.MAPPING_ID_OPT;

public class ExtractionMappingServiceImpl implements ExtractionMappingService {
    private final ExtractionMappingRepository mappings;
    private final TaskManager taskManager;

    @Inject
    public ExtractionMappingServiceImpl(ExtractionMappingRepository mappings, TaskManager taskManager) {
        this.mappings = mappings;
        this.taskManager = taskManager;
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

    @Override
    public Optional<ExtractionMapping> get(String projectId, String id) {
        return mappings.get(projectId, id);
    }

    @Override
    public String run(String projectId, String id, User user) throws UnknownExtractionMapping, IOException {
        if (mappings.get(projectId, id).isEmpty()) {
            throw new UnknownExtractionMapping(projectId, id);
        }
        Map<String, Object> args = new HashMap<>(Map.of(DEFAULT_PROJECT_OPT, projectId, MAPPING_ID_OPT, id));
        return taskManager.startTask(StructuredEntityExtractionTask.class, user, args);
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
