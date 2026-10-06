package org.icij.datashare.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
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

    default Optional<Property> property(String type, String name) {
        return type(type).map(found -> found.properties().get(name));
    }

    /**
     * Checks the entity's types and properties against this model's structure. Returns every
     * violation found, or an empty list if the entity is structurally valid.
     */
    default List<String> validate(ModelEntity entity) {
        Set<String> filled = entity.properties().keySet().stream().filter(property -> !isBlank(entity, property))
                                   .collect(Collectors.toSet());
        return violations(entity.type(), entity.properties().keySet(), filled);
    }

    /**
     * Checks that a type accepts these properties and that they cover what it requires, with no
     * value at hand: an extraction mapping declares which properties it fills, not what they hold.
     */
    default List<String> validateShape(String type, Set<String> properties) {
        return violations(type, properties, properties);
    }

    private List<String> violations(String typeName, Set<String> properties, Set<String> filled) {
        List<String> violations = new ArrayList<>();
        Optional<EntityType> found = type(typeName);
        if (found.isEmpty()) {
            violations.add("unknown type '" + typeName + "' in model '" + name() + "'");
            return violations;
        }
        EntityType type = found.get();
        if (type.isAbstract()) {
            violations.add("type '" + type.name() + "' is abstract and cannot be instantiated");
        }
        for (String property : new TreeSet<>(properties)) {
            Property declared = type.properties().get(property);
            if (declared == null) {
                violations.add("no property '" + property + "' on '" + type.name() + "'");
            } else if (declared.stub()) {
                violations.add("property '" + property + "' is a stub: it is inferred from the '" + declared.range() +
                               "' relation rather than written");
            }
        }
        type.required().stream().filter(required -> !filled.contains(required)).forEach(
                required -> violations.add("type '" + type.name() + "' requires '" + required + "'"));
        if (type.edge() != null) {
            Stream.of(type.edge().source(), type.edge().target()).filter(end -> !type.required().contains(end))
                  .filter(end -> !filled.contains(end))
                  .forEach(end -> violations.add("edge type '" + type.name() + "' needs '" + end + "'"));
        }
        return violations;
    }

    private static boolean isBlank(ModelEntity entity, String property) {
        return entity.properties().getOrDefault(property, List.of()).stream().allMatch(String::isBlank);
    }
}
