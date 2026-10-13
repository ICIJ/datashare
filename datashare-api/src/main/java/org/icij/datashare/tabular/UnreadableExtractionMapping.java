package org.icij.datashare.tabular;

public class UnreadableExtractionMapping extends RuntimeException {
    public final String id;
    /** Read from its own column, so the owner stays known when the definition no longer parses. */
    public final String userId;

    public UnreadableExtractionMapping(String id, String userId, Throwable cause) {
        super("mapping '%s' could not be read: %s".formatted(id, cause.getMessage()), cause);
        this.id = id;
        this.userId = userId;
    }
}
