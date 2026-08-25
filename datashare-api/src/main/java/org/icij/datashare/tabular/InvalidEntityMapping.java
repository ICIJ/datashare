package org.icij.datashare.tabular;

public class InvalidEntityMapping extends IllegalArgumentException {
    public final String type;

    public InvalidEntityMapping(String type) {
        super("entity type '%s' has no key column".formatted(type));
        this.type = type;
    }
}
