package org.icij.datashare.tabular;

import java.util.List;

public class InvalidExtractionMapping extends IllegalArgumentException {
    public final List<String> violations;

    public InvalidExtractionMapping(String id, List<String> violations) {
        super("mapping '%s' does not validate: %s".formatted(id, violations));
        this.violations = List.copyOf(violations);
    }
}
