package org.icij.datashare.tasks;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

public enum TaskType {
    BATCH_SEARCH(
        "org.icij.datashare.tasks.BatchSearchRunner",
        "org.icij.datashare.tasks.BatchSearchRunnerProxy"
    ),
    BATCH_DOWNLOAD("org.icij.datashare.tasks.BatchDownloadRunner"),
    INDEX("org.icij.datashare.tasks.IndexTask"),
    SCAN("org.icij.datashare.tasks.ScanTask"),
    SCAN_INDEX("org.icij.datashare.tasks.ScanIndexTask"),
    ENQUEUE_FROM_INDEX("org.icij.datashare.tasks.EnqueueFromIndexTask"),
    EXTRACT_NLP("org.icij.datashare.tasks.ExtractNlpTask"),
    BATCH_NLP("org.icij.datashare.tasks.BatchNlpTask"),
    CREATE_NLP_BATCHES("org.icij.datashare.tasks.CreateNlpBatchesFromIndex"),
    CATEGORIZE("org.icij.datashare.tasks.CategorizeTask"),
    DEDUPLICATE("org.icij.datashare.tasks.DeduplicateTask"),
    ARTIFACT("org.icij.datashare.tasks.ArtifactTask"),
    GEN_API_KEY("org.icij.datashare.tasks.GenApiKeyTask"),
    DEL_API_KEY("org.icij.datashare.tasks.DelApiKeyTask"),
    GET_API_KEY("org.icij.datashare.tasks.GetApiKeyTask"),
    GRANT_ADMIN_POLICY("org.icij.datashare.tasks.GrantAdminPolicyTask");

    private final Set<String> names;

    TaskType(String... names) {
        this.names = Set.of(names);
    }

    public Set<String> getNames() { return names; }

    public static Optional<TaskType> fromName(String fqdn) {
        if (fqdn == null) return Optional.empty();
        return Arrays.stream(values())
            .filter(t -> t.names.contains(fqdn))
            .findFirst();
    }

    /**
     * Convert a String to a TaskType.
     * You can use this one instead of {@link TaskType#valueOf(String)} to have a more specific exception.
     * @param type String type
     * @return TaskType
     * @throws UnknownTaskType if the type is not known
     */
    public static TaskType fromString(String type) {
        try {
            return TaskType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new UnknownTaskType(e);
        }
    }
}
