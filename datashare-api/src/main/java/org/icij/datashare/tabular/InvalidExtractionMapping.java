package org.icij.datashare.tabular;

import org.icij.datashare.model.TargetModel;

import java.util.List;

public class InvalidExtractionMapping extends IllegalArgumentException {
    public final List<TargetModel.Violation> violations;

    public InvalidExtractionMapping(String id, List<TargetModel.Violation> violations) {
        super("mapping '%s' does not validate: %s".formatted(id,
                violations.stream().map(TargetModel.Violation::message).toList()));
        this.violations = List.copyOf(violations);
    }
}
