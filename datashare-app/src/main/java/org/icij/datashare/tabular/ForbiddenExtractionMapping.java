package org.icij.datashare.tabular;

public class ForbiddenExtractionMapping extends RuntimeException {
    public final String projectId;
    public final String id;
    public final String userId;

    public ForbiddenExtractionMapping(String projectId, String id, String userId) {
        super("user '%s' may not use mapping '%s' in project '%s'".formatted(userId, id, projectId));
        this.projectId = projectId;
        this.id = id;
        this.userId = userId;
    }
}
