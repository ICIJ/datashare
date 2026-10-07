package org.icij.datashare.tabular;

public class UnknownExtractionMapping extends Exception {
    public final String projectId;
    public final String id;

    public UnknownExtractionMapping(String projectId, String id) {
        super("no mapping '%s' in project '%s'".formatted(id, projectId));
        this.projectId = projectId;
        this.id = id;
    }
}
