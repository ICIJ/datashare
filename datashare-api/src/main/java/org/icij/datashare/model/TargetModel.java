package org.icij.datashare.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
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
        SortedSet<String> names = new TreeSet<>(entity.types());
        if (names.isEmpty()) {
            violations.add(new Violation("entity '" + entity.id() + "' has no type"));
        }
        List<EntityType> types = new ArrayList<>();
        for (String name : names) {
            Optional<EntityType> found = type(name);
            if (found.isEmpty()) {
                violations.add(new Violation("unknown type '" + name + "' in model '" + name() + "'"));
            } else {
                types.add(found.get());
            }
        }
        if (!types.isEmpty() && types.stream().allMatch(EntityType::isAbstract)) {
            violations.add(new Violation("every type in " + types.stream().map(EntityType::name).toList()
                    + " is abstract and cannot be instantiated"));
        }
        if (!types.isEmpty()) {
            for (String property : entity.properties().keySet()) {
                List<Property> declarations = types.stream()
                        .map(type -> type.properties().get(property))
                        .filter(Objects::nonNull)
                        .toList();
                if (declarations.isEmpty()) {
                    violations.add(new Violation("no property '" + property + "' on " + names));
                } else if (declarations.stream().allMatch(Property::stub)) {
                    violations.add(new Violation("property '" + property + "' is a stub: it is inferred from the '"
                            + declarations.get(0).range() + "' relation rather than written"));
                }
            }
        }
        Set<String> reported = new HashSet<>();
        for (EntityType type : types) {
            type.required().stream()
                    .filter(required -> isBlank(entity, required) && reported.add(required))
                    .forEach(required ->
                            violations.add(new Violation("type '" + type.name() + "' requires '" + required + "'")));
            if (type.edge() != null) {
                Stream.of(type.edge().source(), type.edge().target())
                        .filter(end -> !type.required().contains(end))
                        .filter(end -> isBlank(entity, end))
                        .forEach(end -> violations.add(
                                new Violation("edge type '" + type.name() + "' needs '" + end + "'")));
            }
        }
        return violations;
    }

    private static boolean isBlank(ModelEntity entity, String property) {
        return entity.properties().getOrDefault(property, List.of()).stream().allMatch(String::isBlank);
    }
}
