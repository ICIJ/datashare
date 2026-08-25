package org.icij.datashare.tabular;

public class InvalidEntityMapping extends IllegalArgumentException {
    public InvalidEntityMapping(String type) {
        super("entity type '%s' has no key column".formatted(type));
    }
}
