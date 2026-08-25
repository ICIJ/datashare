package org.icij.datashare.tabular;

public class UnreadableExtractionMapping extends RuntimeException {
    public final String id;

    public UnreadableExtractionMapping(String id, Throwable cause) {
        super("mapping '%s' could not be read: %s".formatted(id, cause.getMessage()), cause);
        this.id = id;
    }
}
