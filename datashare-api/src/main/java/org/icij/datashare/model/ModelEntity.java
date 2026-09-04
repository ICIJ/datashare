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

/** One type, not a set: the entity id hashes the type, so two types can never share an id and a
 *  multi-type entity cannot exist by construction. */
public record ModelEntity(String model, String id, String type, Set<String> modelVersions,
                          Set<String> documentIds, Map<String, List<String>> properties) {

    public ModelEntity {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(modelVersions, "modelVersions");
        Objects.requireNonNull(documentIds, "documentIds");
        Objects.requireNonNull(properties, "properties");
        modelVersions = Set.copyOf(modelVersions);
        documentIds = Set.copyOf(documentIds);
        Map<String, List<String>> copy = new LinkedHashMap<>();
        properties.forEach((property, values) -> copy.put(property, List.copyOf(values)));
        properties = Collections.unmodifiableMap(copy);
    }

    /** Folds statements into the entity they describe, one at a time: only its distinct values are
     *  held, so a group larger than memory is not built to be collapsed. Properties and values come
     *  out in natural order, not in the order the statements arrived, so a database collation never
     *  decides what an entity looks like. */
    public static ModelEntity from(Iterable<Statement> statements, Set<String> modelVersions) {
        SortedSet<String> ids = new TreeSet<>();
        SortedSet<String> models = new TreeSet<>();
        SortedSet<String> types = new TreeSet<>();
        Set<String> documentIds = new TreeSet<>();
        Map<String, SortedSet<String>> values = new TreeMap<>();
        for (Statement statement : statements) {
            ids.add(statement.entityId());
            models.add(statement.model());
            types.add(statement.entityType());
            documentIds.add(statement.provenance().documentId());
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
        if (types.size() > 1) {
            throw new IllegalArgumentException("statements give the entity " + types.size() + " types: " + types);
        }
        Map<String, List<String>> properties = new LinkedHashMap<>();
        values.forEach((property, distinct) -> properties.put(property, List.copyOf(distinct)));
        return new ModelEntity(models.first(), ids.first(), types.first(), modelVersions, documentIds, properties);
    }
}
