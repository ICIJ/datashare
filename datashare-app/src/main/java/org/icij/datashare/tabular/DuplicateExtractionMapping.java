package org.icij.datashare.tabular;

public class DuplicateExtractionMapping extends Exception {
    /** Each reason calls for a different fix, so the message has to say which one it is. */
    public enum Reason {
        ID_TAKEN(": a mapping is immutable, so save this one under a new id"),
        DIFFERENT_DEFINITION(" with a different definition: save the change under a new id"),
        UNREADABLE_DEFINITION(" but its stored definition no longer reads");
        private final String explanation;

        Reason(String explanation) {
            this.explanation = explanation;
        }
    }

    public final String projectId;
    public final String id;
    public final Reason reason;

    public DuplicateExtractionMapping(String projectId, String id, Reason reason) {
        super("mapping '%s' already exists in project '%s'%s".formatted(id, projectId, reason.explanation));
        this.projectId = projectId;
        this.id = id;
        this.reason = reason;
    }
}
