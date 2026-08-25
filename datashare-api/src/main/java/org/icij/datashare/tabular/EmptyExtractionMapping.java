package org.icij.datashare.tabular;

public class EmptyExtractionMapping extends IllegalArgumentException {
    public EmptyExtractionMapping(String id) {
        super("mapping '%s' builds no entity".formatted(id));
    }
}
