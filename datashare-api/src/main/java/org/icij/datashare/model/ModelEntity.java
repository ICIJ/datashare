package org.icij.datashare.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

public record ModelEntity(String id, Set<String> types, Map<String, List<String>> properties) {

    public ModelEntity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(types, "types");
        Objects.requireNonNull(properties, "properties");
        types = Set.copyOf(types);
        Map<String, List<String>> copy = new LinkedHashMap<>();
        properties.forEach((property, values) -> copy.put(property, List.copyOf(values)));
        properties = Collections.unmodifiableMap(copy);
    }

    public static ModelEntity from(Collection<Statement> statements) {
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("cannot rebuild an entity from no statement");
        }
        SortedSet<String> ids = new TreeSet<>();
        SortedSet<String> models = new TreeSet<>();
        Set<String> types = new LinkedHashSet<>();
        Map<String, Set<String>> values = new LinkedHashMap<>();
        for (Statement statement : statements) {
            ids.add(statement.entityId());
            models.add(statement.model());
            types.add(statement.entityType());
            values.computeIfAbsent(statement.property(), property -> new LinkedHashSet<>()).add(statement.value());
        }
        if (ids.size() > 1) {
            throw new IllegalArgumentException("statements belong to " + ids.size() + " entities: " + ids);
        }
        if (models.size() > 1) {
            throw new IllegalArgumentException("statements belong to " + models.size() + " models: " + models);
        }
        Map<String, List<String>> properties = new LinkedHashMap<>();
        values.forEach((property, distinct) -> properties.put(property, new ArrayList<>(distinct)));
        return new ModelEntity(ids.first(), types, properties);
    }
}
