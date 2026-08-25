package org.icij.datashare.tabular;

import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.TargetModel;
import org.icij.datashare.model.TargetModelRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record ExtractionMapping(String id, String projectId, String userId, String name, String model,
                                String documentId, RowSourceOptions options,
                                Map<String, EntityMapping> entities) {

    public ExtractionMapping {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(entities, "entities");
        options = options == null ? RowSourceOptions.defaults() : options;
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
                throw new InvalidPropertyMapping(
                        "a property is filled from exactly one of columns, literal or entity, got " + sources);
            }
            if (join != null && columns.size() < 2) {
                throw new InvalidPropertyMapping("'join' needs more than one column");
            }
            if (format != null && columns.isEmpty()) {
                throw new InvalidPropertyMapping("'format' applies to columns only");
            }
        }
    }

    /** Checks every entity type and property against the target model. Called on save, not on read,
     *  so a mapping stored before the ontology moved underneath it still loads. Aliases and property
     *  names are walked in sorted order, so the same mapping always reports the same violations in
     *  the same order. */
    public List<TargetModel.Violation> validate() {
        TargetModel target = TargetModelRegistry.get(model);
        List<TargetModel.Violation> violations = new ArrayList<>();
        for (String alias : new TreeSet<>(entities.keySet())) {
            EntityMapping entity = entities.get(alias);
            target.validate(probe(alias, entity)).forEach(violation ->
                    violations.add(new TargetModel.Violation("entity '" + alias + "': " + violation.message())));
            for (String property : new TreeSet<>(entity.properties().keySet())) {
                String reference = entity.properties().get(property).entity();
                if (reference != null && !entities.containsKey(reference)) {
                    violations.add(new TargetModel.Violation("property '" + property + "' on entity '" + alias
                            + "' references unknown entity '" + reference + "'"));
                }
            }
        }
        return violations;
    }

    // A mapping declares which properties it fills, not what they will hold, so the model's own
    // checker runs against a probe carrying a placeholder value per mapped property. That makes the
    // model's "required property is blank" test read as "required property is not mapped", and picks
    // up the required-property and edge-endpoint rules a mapping-only check would miss.
    private ModelEntity probe(String alias, EntityMapping entity) {
        Map<String, List<String>> properties = new LinkedHashMap<>();
        new TreeSet<>(entity.properties().keySet()).forEach(property -> properties.put(property, List.of("?")));
        return new ModelEntity(alias, Set.of(entity.type()), properties);
    }
}
