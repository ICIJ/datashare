package org.icij.datashare.tabular;

public class EmptyExtractionMapping extends IllegalArgumentException {
    public final String id;

    public EmptyExtractionMapping(String id) {
        super("mapping '%s' builds no entity".formatted(id));
        this.id = id;
    }
}
