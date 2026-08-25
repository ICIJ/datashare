package org.icij.datashare.tabular;

import org.icij.datashare.model.EntityType;
import org.icij.datashare.model.Property;
import org.icij.datashare.model.TargetModel;
import org.icij.datashare.model.TargetModelRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ExtractionMapping(String id, String projectId, String userId, String name, String model,
                                String documentId, RowSourceOptions options,
                                Map<String, EntityMapping> entities) {

    public ExtractionMapping {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(entities, "entities");
        if (entities.isEmpty()) {
            throw new EmptyExtractionMapping(id);
        }
        entities = Map.copyOf(entities);
        TargetModelRegistry.get(Objects.requireNonNull(model, "model"));
    }

    public record EntityMapping(String type, List<String> keys, Map<String, PropertyMapping> properties) {
        public EntityMapping {
            Objects.requireNonNull(type, "type");
            keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
            if (keys.isEmpty()) {
                throw new InvalidEntityMapping(type);
            }
            properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
        }
    }

    public record PropertyMapping(List<String> columns, String join, String literal, String entity, String format) {
        public PropertyMapping {
            columns = List.copyOf(columns == null ? List.of() : columns);
            long sources = (columns.isEmpty() ? 0 : 1) + (literal == null ? 0 : 1) + (entity == null ? 0 : 1);
            if (sources != 1) {
                throw new InvalidPropertyMapping("sources",
                        "a property is filled from exactly one of columns, literal or entity, got " + sources);
            }
            if (join != null && columns.size() < 2) {
                throw new InvalidPropertyMapping("join", "'join' needs more than one column");
            }
            if (format != null && columns.isEmpty()) {
                throw new InvalidPropertyMapping("format", "'format' applies to columns only");
            }
        }
    }

    /** Checks every entity type and property against the target model. Called on save, not on read,
     *  so a mapping stored before the ontology moved underneath it still loads. */
    public List<TargetModel.Violation> validate() {
        TargetModel target = TargetModelRegistry.get(model);
        List<TargetModel.Violation> violations = new ArrayList<>();
        entities.forEach((alias, entity) -> {
            Optional<EntityType> type = target.type(entity.type());
            if (type.isEmpty()) {
                violations.add(new TargetModel.Violation(
                        "unknown type '" + entity.type() + "' for entity '" + alias + "' in model '" + model + "'"));
                return;
            }
            if (type.get().isAbstract()) {
                violations.add(new TargetModel.Violation(
                        "type '" + entity.type() + "' for entity '" + alias + "' is abstract and cannot be instantiated"));
            }
            entity.properties().forEach((name, mapping) -> {
                Optional<Property> property = target.property(entity.type(), name);
                if (property.isEmpty()) {
                    violations.add(new TargetModel.Violation("no property '" + name + "' on '" + entity.type() + "'"));
                } else if (property.get().stub()) {
                    violations.add(new TargetModel.Violation(
                            "property '" + name + "' on '" + entity.type() + "' is a stub: it is inferred from the '"
                                    + property.get().range() + "' relation rather than written"));
                }
                if (mapping.entity() != null && !entities.containsKey(mapping.entity())) {
                    violations.add(new TargetModel.Violation(
                            "property '" + name + "' on entity '" + alias + "' references unknown entity '" + mapping.entity() + "'"));
                }
            });
        });
        return violations;
    }
}
