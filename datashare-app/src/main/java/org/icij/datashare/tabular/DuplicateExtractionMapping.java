package org.icij.datashare.tabular;

public class DuplicateExtractionMapping extends Exception {
    public final String projectId;
    public final String id;

    public DuplicateExtractionMapping(String projectId, String id) {
        super("mapping '%s' already exists in project '%s'".formatted(id, projectId));
        this.projectId = projectId;
        this.id = id;
    }
}
