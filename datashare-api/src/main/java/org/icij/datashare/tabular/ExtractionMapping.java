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

    public record EntityMapping(String type, String keyLiteral, List<String> keys,
                                Map<String, PropertyMapping> properties) {
        public EntityMapping(String type, List<String> keys, Map<String, PropertyMapping> properties) {
            this(type, null, keys, properties);
        }

        public EntityMapping {
            Objects.requireNonNull(type, "type");
            // Keys name header columns, so they get the cleaning headers get: a key pasted with a
            // non-breaking space still matches the header it names. No key at all is a valid
            // mapping: each row is then its own record, identified by where it sits in the file.
            keys = List.copyOf(Objects.requireNonNull(keys, "keys")).stream().map(Row::clean).toList();
            properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
        }
    }

    public record PropertyMapping(List<String> columns, String join, String literal, String entity, String format) {
        public PropertyMapping {
            // Column names get the cleaning headers get, and a literal gets the cleaning cells get:
            // a mapping authored by copy-paste behaves like the file it was copied from.
            columns = List.copyOf(columns == null ? List.of() : columns).stream().map(Row::clean).toList();
            literal = literal == null ? null : Row.clean(literal);
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

    /** Checks every entity type and property against the target model, plus what no row could
     *  honour: a blank literal, an entity mapping no property, an unusable date format, a NUL in
     *  any string a statement would carry (Statement's constructor aborts on one, so catching it
     *  here is what keeps a bad mapping from killing a run mid-file). The compact constructor only
     *  checks the model is known, so this is the only complete check, run on save and again by the
     *  executor, so a mapping stored before the ontology moved underneath it still loads and fails
     *  only when run. Aliases and property names are walked in sorted order, so the same mapping
     *  always reports the same violations in the same order. */
    public List<TargetModel.Violation> validate() {
        TargetModel target = TargetModelRegistry.get(model);
        List<TargetModel.Violation> violations = new ArrayList<>();
        if (options.sheet() != null && holdsNul(options.sheet())) {
            violations.add(new TargetModel.Violation("the sheet name holds a NUL character"));
        }
        if (holdsNul(documentId)) {
            violations.add(new TargetModel.Violation("the document id holds a NUL character"));
        }
        DateFormats formats = new DateFormats();
        for (String alias : new TreeSet<>(entities.keySet())) {
            EntityMapping entity = entities.get(alias);
            target.validate(probe(alias, entity)).forEach(violation ->
                    violations.add(new TargetModel.Violation("entity '" + alias + "': " + violation.message())));
            if (entity.properties().isEmpty()) {
                violations.add(new TargetModel.Violation("entity '" + alias
                        + "' maps no property, so no row can produce a statement for it"));
            }
            entity.keys().stream().filter(ExtractionMapping::holdsNul).forEach(key ->
                    violations.add(new TargetModel.Violation("entity '" + alias
                            + "' has a key column name holding a NUL character")));
            if (entity.keys().stream().anyMatch(String::isEmpty)) {
                violations.add(new TargetModel.Violation("entity '" + alias
                        + "' has a blank key column name, which no header can match"));
            }
            if (entity.keyLiteral() != null && holdsNul(entity.keyLiteral())) {
                violations.add(new TargetModel.Violation("entity '" + alias
                        + "' has a key literal holding a NUL character"));
            }
            if (entity.keyLiteral() != null && entity.keys().isEmpty()) {
                violations.add(new TargetModel.Violation("entity '" + alias
                        + "' has a key literal but no key, and a row-scoped id carries no literal"));
            }
            for (String property : new TreeSet<>(entity.properties().keySet())) {
                reference(target, alias, entity, property).ifPresent(violations::add);
                runtime(alias, entity.properties().get(property), property, formats).forEach(violations::add);
            }
        }
        return violations;
    }

    /** {@link #validate()}, throwing: the save path and the executor refuse an unusable mapping
     *  the same way. */
    public void requireValid() {
        List<TargetModel.Violation> violations = validate();
        if (!violations.isEmpty()) {
            throw new InvalidExtractionMapping(id, violations);
        }
    }

    private List<TargetModel.Violation> runtime(String alias, PropertyMapping mapped, String property,
                                                DateFormats formats) {
        List<TargetModel.Violation> violations = new ArrayList<>();
        String where = "property '" + property + "' on entity '" + alias + "' ";
        if (mapped.literal() != null && mapped.literal().isBlank()) {
            violations.add(new TargetModel.Violation(where + "has a blank literal, which no row can store"));
        }
        if (mapped.literal() != null && holdsNul(mapped.literal())) {
            violations.add(new TargetModel.Violation(where + "has a literal holding a NUL character"));
        }
        if (mapped.join() != null && holdsNul(mapped.join())) {
            violations.add(new TargetModel.Violation(where + "has a join separator holding a NUL character"));
        }
        mapped.columns().stream().filter(ExtractionMapping::holdsNul).forEach(column ->
                violations.add(new TargetModel.Violation(where + "has a column name holding a NUL character")));
        if (mapped.columns().stream().anyMatch(String::isEmpty)) {
            violations.add(new TargetModel.Violation(where + "has a blank column name, which no header can match"));
        }
        if (mapped.format() != null) {
            try {
                formats.declare(mapped.format());
            } catch (UnusableDateFormat unusable) {
                violations.add(new TargetModel.Violation(where + "has an unusable format: "
                        + unusable.getMessage()));
            }
        }
        return violations;
    }

    private static boolean holdsNul(String text) {
        return text.indexOf('\u0000') >= 0;
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
        return new ModelEntity(model, alias, entity.type(), Set.of(), Set.of(documentId), properties);
    }
}
