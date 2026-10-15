package org.icij.datashare.tabular;

import org.icij.datashare.model.Property;
import org.icij.datashare.model.TargetModel;
import org.icij.datashare.model.TargetModelRegistry;
import org.icij.datashare.text.Project;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import static java.util.stream.Collectors.toUnmodifiableMap;

/**
 * @param rootId the container an embedded document was extracted from, or null for a root document:
 *               elasticsearch routes an embedded document by its root, so a mapping over a CSV
 *               inside a ZIP cannot be read without it.
 */
public record ExtractionMapping(String id, String projectId, String userId, String name, String model, String documentId, String rootId, RowSourceOptions options, Map<String, EntityMapping> entities) {
    /** The width of the extraction_mapping id column: Postgres refuses a longer id with an unchecked
     *  database error, and SQLite stores it as-is, so the limit is enforced here for both. */
    public static final int MAX_ID_LENGTH = 96;
    /** A mapping id names the mapping in a URL path segment, and a stored id can never change. */
    private static final Pattern ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0," + (MAX_ID_LENGTH - 1) + "}$");

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
        entities.forEach((alias, entity) -> {
            if (entity == null) {
                throw new MalformedExtractionMapping("field 'entities.%s' holds a null".formatted(alias));
            }
        });
        entities = Map.copyOf(entities);
        TargetModelRegistry.get(Objects.requireNonNull(model, "model"));
    }

    public record EntityMapping(String type, String keyLiteral, List<String> keys, String idColumn, Map<String, String> filters, Map<String, PropertyMapping> properties) {
        public EntityMapping(String type, List<String> keys, Map<String, PropertyMapping> properties) {
            this(type, null, keys, null, Map.of(), properties);
        }

        public EntityMapping(String type, String keyLiteral, List<String> keys,
                             Map<String, PropertyMapping> properties) {
            this(type, keyLiteral, keys, null, Map.of(), properties);
        }

        public EntityMapping(String type, String keyLiteral, List<String> keys, String idColumn,
                             Map<String, PropertyMapping> properties) {
            this(type, keyLiteral, keys, idColumn, Map.of(), properties);
        }

        public EntityMapping {
            Objects.requireNonNull(type, "type");
            // Keys name header columns, so they get the cleaning headers get: a key pasted with a
            // non-breaking space still matches the header it names, and a column named twice counts
            // once, since hashing one cell twice would name a different entity than the same mapping
            // written without the repeat. No key at all is a valid mapping: each row is then its own
            // record, identified by where it sits in the file. The key literal is hashed alongside
            // the key values and gets the same cleaning: an invisible character in one of two
            // mappings meant to agree would otherwise split one entity in two, which is the merge
            // the literal exists to control. An id column is the third way to identify an entity, and
            // the only one that does not hash: the value is the id, so two mappings reading the same
            // id column merge their entities deliberately, which is what a dump of globally-named
            // entities needs and the footgun the option carries.
            keys = (keys == null ? List.<String>of() : keys).stream().map(Row::clean).distinct().toList();
            keyLiteral = keyLiteral == null ? null : Row.clean(keyLiteral);
            idColumn = idColumn == null ? null : Row.clean(idColumn);
            // A filter names a header column and holds a cell's value, so each side gets the cleaning
            // that side gets elsewhere: the comparison then runs on two values cleaned the same way,
            // which is the rule every other column comparison follows.
            filters = (filters == null ? Map.<String, String>of() : filters).entrySet().stream().collect(
                    toUnmodifiableMap(entry -> Row.clean(entry.getKey()), entry -> Row.clean(entry.getValue())));
            properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
        }
    }

    public record PropertyMapping(List<String> columns, String join, String literal, String entity, String dateFormat) {
        public PropertyMapping {
            // Column names get the cleaning headers get, and a literal gets the cleaning cells get:
            // a mapping authored by copy-paste behaves like the file it was copied from. JSON is the
            // exception: a record carries its own keys and JsonRowSource leaves them untouched, so a
            // key holding an invisible character is named by no mapping and the read fails on row 1.
            // A column named twice counts once, so a join cannot glue a cell to itself, and 'join'
            // then reads the count that matters: two names for one column are one column.
            columns = (columns == null ? List.<String>of() : columns).stream().map(Row::clean).distinct().toList();
            literal = literal == null ? null : Row.clean(literal);
            long sources = (columns.isEmpty() ? 0 : 1) + (literal == null ? 0 : 1) + (entity == null ? 0 : 1);
            if (sources != 1) {
                throw new InvalidPropertyMapping(
                        "a property is filled from exactly one of columns, literal or entity, got " + sources);
            }
            if (join != null && columns.size() < 2) {
                throw new InvalidPropertyMapping("'join' needs more than one column");
            }
            if (dateFormat != null && columns.isEmpty()) {
                throw new InvalidPropertyMapping("'dateFormat' applies to columns only");
            }
        }
    }

    /** Checks every entity type and property against the target model, plus what no row could
     *  honour: a blank literal, an entity mapping no property, an unusable date format, a NUL in
     *  any string a statement would carry (Statement's constructor aborts on one, so catching it
     *  here is what keeps a bad mapping from killing a run mid-file). The compact constructor only
     *  checks the model is known, so this is the only complete check, run on save and again by the
     *  builder, so a mapping stored before the ontology moved underneath it still loads and fails
     *  only when run. Aliases and property names are walked in sorted order, so the same mapping
     *  always reports the same violations in the same order. */
    public List<String> validate() {
        TargetModel target = TargetModelRegistry.get(model);
        List<String> violations = new ArrayList<>();
        refuseUnusableIds(violations);
        refuseNul(violations, options.sheet(), "the sheet name");
        refuseNul(violations, documentId, "the document id");
        refuseNul(violations, rootId, "the root id");
        DateFormats formats = new DateFormats();
        for (String alias : new TreeSet<>(entities.keySet())) {
            EntityMapping entity = entities.get(alias);
            target.validateShape(entity.type(), entity.properties().keySet())
                  .forEach(violation -> violations.add("entity '" + alias + "': " + violation));
            if (entity.properties().isEmpty()) {
                violations.add("entity '" + alias + "' maps no property, so no row can produce a statement for it");
            }
            entity.keys().forEach(key -> refuseNul(violations, key, "the key column name of entity '" + alias + "'"));
            if (entity.keys().stream().anyMatch(String::isEmpty)) {
                violations.add("entity '" + alias + "' has a blank key column name, which no header can match");
            }
            refuseNul(violations, entity.keyLiteral(), "the key literal of entity '" + alias + "'");
            if (entity.keyLiteral() != null && entity.keyLiteral().isBlank()) {
                violations.add("entity '" + alias + "' has a blank key literal, which hashes like no literal at all");
            }
            if (entity.keyLiteral() != null && entity.keys().isEmpty()) {
                violations.add(
                        "entity '" + alias + "' has a key literal but no key, and a row-scoped id carries no literal");
            }
            refuseNul(violations, entity.idColumn(), "the id column name of entity '" + alias + "'");
            if (entity.idColumn() != null && entity.idColumn().isEmpty()) {
                violations.add("entity '" + alias + "' has a blank id column name, which no header can match");
            }
            if (entity.idColumn() != null && !entity.keys().isEmpty()) {
                violations.add(
                        "entity '" + alias + "' has both an id column and a key, and an id is read one way only");
            }
            new TreeMap<>(entity.filters()).forEach((column, value) -> {
                refuseNul(violations, column, "the filter column name of entity '" + alias + "'");
                refuseNul(violations, value, "the filter value of entity '" + alias + "'");
                if (column.isEmpty()) {
                    violations.add("entity '" + alias + "' has a blank filter column name, which no header can match");
                }
                if (value.isEmpty()) {
                    violations.add("entity '" + alias + "' has a blank filter value on column '" + column +
                                   "', which no row can match");
                }
            });
            for (String property : new TreeSet<>(entity.properties().keySet())) {
                reference(target, alias, entity, property).ifPresent(violations::add);
                runtime(alias, entity.properties().get(property), property, formats).forEach(violations::add);
            }
        }
        return violations;
    }

    /** {@link #validate()}, throwing: the save path and the builder refuse an unusable mapping
     *  the same way. */
    public void requireValid() {
        List<String> violations = validate();
        if (!violations.isEmpty()) {
            throw new InvalidExtractionMapping(id, violations);
        }
    }

    private List<String> runtime(String alias, PropertyMapping mapped, String property, DateFormats formats) {
        List<String> violations = new ArrayList<>();
        String where = "property '" + property + "' on entity '" + alias + "' ";
        if (mapped.literal() != null && mapped.literal().isBlank()) {
            violations.add(where + "has a blank literal, which no row can store");
        }
        refuseNul(violations, mapped.literal(), "the literal of " + where.strip());
        refuseNul(violations, mapped.join(), "the join separator of " + where.strip());
        mapped.columns().forEach(column -> refuseNul(violations, column, "the column name of " + where.strip()));
        if (mapped.columns().stream().anyMatch(String::isEmpty)) {
            violations.add(where + "has a blank column name, which no header can match");
        }
        if (mapped.dateFormat() != null) {
            try {
                formats.declare(mapped.dateFormat());
            } catch (UnusableDateFormat unusable) {
                violations.add(where + "has an unusable date format: " + unusable.getMessage());
            }
        }
        return violations;
    }

    // The run names the mapping by its id and refuses a project id no project can have, so either
    // flaw would store a mapping that no run can ever use, and its id could not be saved again.
    private void refuseUnusableIds(List<String> violations) {
        if (!ID_PATTERN.matcher(id).matches()) {
            violations.add("the mapping id '" + id + "' is not a letter or digit followed by " +
                           "letters, digits, '.', '_' or '-', at most " + MAX_ID_LENGTH + " characters in all");
        }
        refuseNul(violations, name, "the mapping name");
        if (!Project.NAME_PATTERN.matcher(projectId).matches()) {
            violations.add("'" + projectId + "' is not a valid project id");
        }
    }

    // Every string a statement would carry is refused the same way and worded the same way: a NUL
    // reaches Statement's constructor, which aborts, so a mapping holding one has to fail at save.
    private static void refuseNul(List<String> violations, String text, String what) {
        if (text != null && text.indexOf('\u0000') >= 0) {
            violations.add(what + " holds a NUL character");
        }
    }

    /** A cross-reference has to point at an entity the mapping declares, through a property that
     *  takes entities, whose declared range the referenced type satisfies: an edge pointing at the
     *  wrong kind of entity is invalid in the target model, not just in this mapping. */
    private Optional<String> reference(TargetModel target, String alias, EntityMapping entity, String property) {
        String reference = entity.properties().get(property).entity();
        if (reference == null) {
            return Optional.empty();
        }
        String on = "property '" + property + "' on entity '" + alias + "' ";
        EntityMapping referenced = entities.get(reference);
        if (referenced == null) {
            return Optional.of(on + "references unknown entity '" + reference + "'");
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
            return Optional.of(on + "holds a value, not a reference to an entity");
        }
        return target.type(referenced.type())
                     .filter(type -> !type.name().equals(range) && !type.ancestors().contains(range))
                     .map(type -> on + "needs a '" + range + "', but entity '" + reference + "' is a '" + type.name() +
                                  "'");
    }
}
