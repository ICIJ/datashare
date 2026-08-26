package org.icij.datashare.tabular;

import org.icij.datashare.model.Statement;
import org.icij.datashare.text.Hasher;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import static java.util.stream.Collectors.joining;

/**
 * Turns a row into the statements a mapping asks for. One instance per run: rows are consumed on one
 * thread, and nothing here reads or writes anything, so the caller owns the source and the store.
 */
public class MappingExecutor {
    private final ExtractionMapping mapping;
    private final Map<String, DateTimeFormatter> formats = new HashMap<>();

    public MappingExecutor(ExtractionMapping mapping) {
        this.mapping = mapping;
        mapping.entities().values().forEach(entity -> entity.properties().values().stream()
                .map(ExtractionMapping.PropertyMapping::format).filter(Objects::nonNull)
                .forEach(format -> formats.computeIfAbsent(format, DateTimeFormatter::ofPattern)));
    }

    public List<Statement> statements(Row row) {
        Map<String, String> ids = identify(row);
        List<Statement> statements = new ArrayList<>();
        for (String alias : new TreeSet<>(ids.keySet())) {
            statements.addAll(statementsOf(alias, ids.get(alias), row, ids));
        }
        return statements;
    }

    private Map<String, String> identify(Row row) {
        Map<String, String> ids = new LinkedHashMap<>();
        mapping.entities().forEach((alias, entity) -> {
            List<String> keys = entity.keys().stream().map(column -> cell(row, column).strip()).sorted().toList();
            if (keys.stream().noneMatch(String::isEmpty)) {
                ids.put(alias, id(entity.type(), keys));
            }
        });
        return ids;
    }

    // Sorted values, no column name and no alias, so two mappings that call the same identifier
    // differently still land on one entity. NUL-joined for the reason Statement.id is: a cell can
    // hold any printable character.
    private String id(String type, List<String> keys) {
        List<String> parts = new ArrayList<>(List.of(mapping.model(), type));
        parts.addAll(keys);
        return Hasher.SHA_384.hash(String.join("\u0000", parts));
    }

    private List<Statement> statementsOf(String alias, String entityId, Row row, Map<String, String> ids) {
        ExtractionMapping.EntityMapping entity = mapping.entities().get(alias);
        List<Statement> statements = new ArrayList<>();
        for (String property : new TreeSet<>(entity.properties().keySet())) {
            ExtractionMapping.PropertyMapping mapped = entity.properties().get(property);
            if (mapped.literal() != null) {
                add(statements, entityId, entity.type(), property, new Value(mapped.literal(), null), "", row);
            } else if (mapped.entity() != null) {
                add(statements, entityId, entity.type(), property, new Value(ids.get(mapped.entity()), null), "", row);
            } else if (mapped.join() != null) {
                add(statements, entityId, entity.type(), property, value(mapped.columns().stream()
                                .map(column -> cell(row, column)).filter(cell -> !cell.isBlank())
                                .collect(joining(mapped.join())), mapped.format()),
                        String.join(",", mapped.columns()), row);
            } else {
                for (String column : mapped.columns()) {
                    add(statements, entityId, entity.type(), property, value(cell(row, column), mapped.format()),
                            column, row);
                }
            }
        }
        return statements;
    }

    private void add(List<Statement> statements, String entityId, String type, String property,
                     Value value, String column, Row row) {
        if (value.value() == null || value.value().isBlank()) {
            return;
        }
        Statement statement = Statement.of(mapping.model(), entityId, type, property, value.value(),
                new Statement.Provenance(mapping.documentId(), mapping.options().sheet(), row.number(), column));
        statements.add(value.original() == null ? statement : statement.withOriginalValue(value.original()));
    }

    private record Value(String value, String original) { }

    // A date only: 'format' describes how a date column is written, and anything the pattern cannot
    // read is left exactly as it was rather than dropped, so one 'n/a' does not cost a run.
    private Value value(String cell, String format) {
        if (format == null || cell == null || cell.isBlank()) {
            return new Value(cell, null);
        }
        try {
            String iso = LocalDate.parse(cell, formats.get(format)).toString();
            return iso.equals(cell) ? new Value(cell, null) : new Value(iso, cell);
        } catch (DateTimeParseException unparseable) {
            return new Value(cell, null);
        }
    }

    private static String cell(Row row, String column) {
        return row.values().getOrDefault(column, "");
    }
}
