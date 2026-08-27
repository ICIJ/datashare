package org.icij.datashare.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

public record ModelEntity(String model, String id, Set<String> types, Map<String, List<String>> properties) {

    public ModelEntity {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(types, "types");
        Objects.requireNonNull(properties, "properties");
        types = Set.copyOf(types);
        Map<String, List<String>> copy = new LinkedHashMap<>();
        properties.forEach((property, values) -> copy.put(property, List.copyOf(values)));
        properties = Collections.unmodifiableMap(copy);
    }

    /** Folds statements into the entity they describe, one at a time: only its distinct values are
     *  held, so a group larger than memory is not built to be collapsed. Properties and values come
     *  out in natural order, not in the order the statements arrived, so a database collation never
     *  decides what an entity looks like. */
    public static ModelEntity from(Iterable<Statement> statements) {
        SortedSet<String> ids = new TreeSet<>();
        SortedSet<String> models = new TreeSet<>();
        Set<String> types = new TreeSet<>();
        Map<String, SortedSet<String>> values = new TreeMap<>();
        for (Statement statement : statements) {
            ids.add(statement.entityId());
            models.add(statement.model());
            types.add(statement.entityType());
            values.computeIfAbsent(statement.property(), property -> new TreeSet<>()).add(statement.value());
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("cannot rebuild an entity from no statement");
        }
        if (ids.size() > 1) {
            throw new IllegalArgumentException("statements belong to " + ids.size() + " entities: " + ids);
        }
        if (models.size() > 1) {
            throw new IllegalArgumentException("statements belong to " + models.size() + " models: " + models);
        }
        Map<String, List<String>> properties = new LinkedHashMap<>();
        values.forEach((property, distinct) -> properties.put(property, List.copyOf(distinct)));
        return new ModelEntity(models.first(), ids.first(), types, properties);
    }
}
