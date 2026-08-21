package org.icij.datashare.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public interface TargetModel {
    String name();

    String version();

    Optional<EntityType> type(String name);

    String serialize(ModelEntity entity);

    ModelEntity parse(String json);

    record Violation(String message) { }

    default Optional<Property> property(String type, String name) {
        return type(type).map(found -> found.properties().get(name));
    }

    default List<Violation> validate(ModelEntity entity) {
        List<Violation> violations = new ArrayList<>();
        if (entity.types().isEmpty()) {
            violations.add(new Violation("entity '" + entity.id() + "' has no type"));
        }
        List<EntityType> types = new ArrayList<>();
        for (String name : entity.types()) {
            Optional<EntityType> found = type(name);
            if (found.isEmpty()) {
                violations.add(new Violation("unknown type '" + name + "' in model '" + name() + "'"));
            } else {
                if (found.get().isAbstract()) {
                    violations.add(new Violation("type '" + name + "' is abstract and cannot be instantiated"));
                }
                types.add(found.get());
            }
        }
        if (!types.isEmpty()) {
            Map<String, Property> declared = new LinkedHashMap<>();
            types.forEach(type -> type.properties().forEach(declared::putIfAbsent));
            entity.properties().keySet().forEach(property -> {
                Property declaration = declared.get(property);
                if (declaration == null) {
                    violations.add(new Violation("no property '" + property + "' on " + entity.types()));
                } else if (declaration.stub()) {
                    violations.add(new Violation("property '" + property + "' is a stub: it is inferred from the '"
                            + declaration.range() + "' relation rather than written"));
                }
            });
        }
        for (EntityType type : types) {
            type.required().stream().filter(required -> isBlank(entity, required)).forEach(required ->
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
