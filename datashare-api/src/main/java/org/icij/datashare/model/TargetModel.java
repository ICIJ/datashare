package org.icij.datashare.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Stream;

public interface TargetModel {
    String name();

    String version();

    Optional<EntityType> type(String name);

    /**
     * Writes the entity in this model's native JSON form, without validating it: an entity that
     * {@link #validate} would reject (e.g. an abstract type) can still be serialized.
     */
    String serialize(ModelEntity entity);

    /**
     * Reads an entity back from this model's native JSON form. The projection is lossy: a
     * multi-type entity collapses to its most specific single type, so statements, not this JSON,
     * remain the system of record.
     */
    ModelEntity parse(String json);

    record Violation(String message) { }

    default Optional<Property> property(String type, String name) {
        return type(type).map(found -> found.properties().get(name));
    }

    /**
     * Checks the entity's types and properties against this model's structure. Returns every
     * violation found, or an empty list if the entity is structurally valid.
     */
    default List<Violation> validate(ModelEntity entity) {
        List<Violation> violations = new ArrayList<>();
        Optional<EntityType> found = type(entity.type());
        if (found.isEmpty()) {
            violations.add(new Violation("unknown type '" + entity.type() + "' in model '" + name() + "'"));
            return violations;
        }
        EntityType type = found.get();
        if (type.isAbstract()) {
            violations.add(new Violation("type '" + type.name() + "' is abstract and cannot be instantiated"));
        }
        for (String property : new TreeSet<>(entity.properties().keySet())) {
            Property declared = type.properties().get(property);
            if (declared == null) {
                violations.add(new Violation("no property '" + property + "' on '" + type.name() + "'"));
            } else if (declared.stub()) {
                violations.add(new Violation("property '" + property + "' is a stub: it is inferred from the '"
                        + declared.range() + "' relation rather than written"));
            }
        }
        type.required().stream()
                .filter(required -> isBlank(entity, required))
                .forEach(required ->
                        violations.add(new Violation("type '" + type.name() + "' requires '" + required + "'")));
        if (type.edge() != null) {
            Stream.of(type.edge().source(), type.edge().target())
                    .filter(end -> !type.required().contains(end))
                    .filter(end -> isBlank(entity, end))
                    .forEach(end -> violations.add(
                            new Violation("edge type '" + type.name() + "' needs '" + end + "'")));
        }
        return violations;
    }

    private static boolean isBlank(ModelEntity entity, String property) {
        return entity.properties().getOrDefault(property, List.of()).stream().allMatch(String::isBlank);
    }
}
