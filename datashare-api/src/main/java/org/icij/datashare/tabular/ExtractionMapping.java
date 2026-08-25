package org.icij.datashare.tabular;

import org.icij.datashare.model.TargetModel;
import org.icij.datashare.model.TargetModelRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ExtractionMapping(String id, String projectId, String userId, String name, String model,
                                String documentId, RowSourceOptions options,
                                Map<String, EntityMapping> entities) {

    public ExtractionMapping {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(documentId, "documentId");
        options = options == null ? RowSourceOptions.defaults() : options;
        Objects.requireNonNull(entities, "entities");
        if (entities.isEmpty()) {
            throw new IllegalArgumentException("a mapping builds no entity");
        }
        entities = Map.copyOf(entities);
        TargetModelRegistry.get(Objects.requireNonNull(model, "model"));
    }

    public record EntityMapping(String type, List<String> keys, Map<String, PropertyMapping> properties) {
        public EntityMapping {
            Objects.requireNonNull(type, "type");
            keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("entity type '" + type + "' has no key column");
            }
            properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
        }
    }

    public record PropertyMapping(List<String> columns, String join, String literal, String entity, String format) {
        public PropertyMapping {
            columns = List.copyOf(columns == null ? List.of() : columns);
            long sources = (columns.isEmpty() ? 0 : 1) + (literal == null ? 0 : 1) + (entity == null ? 0 : 1);
            if (sources != 1) {
                throw new IllegalArgumentException(
                        "a property is filled from exactly one of columns, literal or entity, got " + sources);
            }
            if (join != null && columns.size() < 2) {
                throw new IllegalArgumentException("'join' needs more than one column");
            }
            if (format != null && columns.isEmpty()) {
                throw new IllegalArgumentException("'format' applies to columns only");
            }
        }
    }

    /** Checks every entity type and property against the target model. Called on save, not on read,
     *  so a mapping stored before the ontology moved underneath it still loads. */
    public List<TargetModel.Violation> validate() {
        TargetModel target = TargetModelRegistry.get(model);
        List<TargetModel.Violation> violations = new ArrayList<>();
        entities.forEach((alias, entity) -> {
            if (target.type(entity.type()).isEmpty()) {
                violations.add(new TargetModel.Violation(
                        "unknown type '" + entity.type() + "' for entity '" + alias + "' in model '" + model + "'"));
                return;
            }
            entity.properties().keySet().stream()
                    .filter(property -> target.property(entity.type(), property).isEmpty())
                    .forEach(property -> violations.add(new TargetModel.Violation(
                            "no property '" + property + "' on '" + entity.type() + "'")));
        });
        return violations;
    }
}
