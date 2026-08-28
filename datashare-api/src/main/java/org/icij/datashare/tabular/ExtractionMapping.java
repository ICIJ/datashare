package org.icij.datashare.tabular;

import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.Property;
import org.icij.datashare.model.TargetModel;
import org.icij.datashare.model.TargetModelRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    /** Checks every entity type and property against the target model. The compact constructor only
     *  checks the model is known, so this is the only complete structural check, and a writer that
     *  skips it stores a mapping the model rejects. Called on save, not on read, so a mapping stored
     *  before the ontology moved underneath it still loads. Aliases and property names are walked in
     *  sorted order, so the same mapping always reports the same violations in the same order. */
    public List<TargetModel.Violation> validate() {
        TargetModel target = TargetModelRegistry.get(model);
        List<TargetModel.Violation> violations = new ArrayList<>();
        for (String alias : new TreeSet<>(entities.keySet())) {
            EntityMapping entity = entities.get(alias);
            target.validate(probe(alias, entity)).forEach(violation ->
                    violations.add(new TargetModel.Violation("entity '" + alias + "': " + violation.message())));
            for (String property : new TreeSet<>(entity.properties().keySet())) {
                reference(target, alias, entity, property).ifPresent(violations::add);
            }
        }
        return violations;
    }

    /** A cross-reference has to point at an entity the mapping declares, through a property that
     *  takes entities, whose declared range the referenced type satisfies: an edge pointing at the
     *  wrong kind of entity is invalid in the target model, not just in this mapping. */
    private Optional<TargetModel.Violation> reference(TargetModel target, String alias, EntityMapping entity,
                                                      String property) {
        String reference = entity.properties().get(property).entity();
        if (reference == null) {
            return Optional.empty();
        }
        String on = "property '" + property + "' on entity '" + alias + "' ";
        EntityMapping referenced = entities.get(reference);
        if (referenced == null) {
            return Optional.of(new TargetModel.Violation(on + "references unknown entity '" + reference + "'"));
        }
        if (target.type(entity.type()).isEmpty()) {
            return Optional.empty();
        }
        Optional<Property> declared = target.property(entity.type(), property);
        if (declared.isEmpty()) {
            return Optional.empty();
        }
        String range = declared.get().range();
        if (range == null) {
            return Optional.of(new TargetModel.Violation(on + "holds a value, not a reference to an entity"));
        }
        return target.type(referenced.type())
                .filter(type -> !type.name().equals(range) && !type.ancestors().contains(range))
                .map(type -> new TargetModel.Violation(on + "needs a '" + range + "', but entity '" + reference
                        + "' is a '" + type.name() + "'"));
    }

    // A mapping declares which properties it fills, not what they will hold, so the model's own
    // checker runs against a probe carrying a placeholder value per mapped property. That makes the
    // model's "required property is blank" test read as "required property is not mapped", and picks
    // up the required-property and edge-endpoint rules a mapping-only check would miss.
    private ModelEntity probe(String alias, EntityMapping entity) {
        Map<String, List<String>> properties = new LinkedHashMap<>();
        new TreeSet<>(entity.properties().keySet()).forEach(property -> properties.put(property, List.of("?")));
        return new ModelEntity(model, alias, Set.of(entity.type()), properties);
    }
}
