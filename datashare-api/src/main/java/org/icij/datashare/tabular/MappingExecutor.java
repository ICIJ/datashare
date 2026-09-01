package org.icij.datashare.tabular;

import org.icij.datashare.model.Statement;
import org.icij.datashare.model.TargetModel;
import org.icij.datashare.text.Hasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

/**
 * Turns a row into the statements a mapping asks for. One instance per run: rows are consumed on one
 * thread, and nothing here reads or writes anything, so the caller owns the source and the store.
 *
 * <p>A row carries one entity's contribution, never the whole entity, so nothing here judges an
 * entity against the model. A mapping that fills a required property on the first row of a group
 * only would otherwise lose every other row of that group, and the entity a row contributes to is
 * whole where its statements are regrouped, not here.
 */
public class MappingExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(MappingExecutor.class);

    /** What a run dropped, and why. ENTITY_ counts one entity of one row, CELL_ counts one cell. */
    public enum Skip { ENTITY_UNIDENTIFIED, ENTITY_EMPTY, CELL_UNREADABLE, CELL_MISSING }

    private final ExtractionMapping mapping;
    private final String documentId;
    private final String sheet;
    private final DateFormats formats = new DateFormats();
    private final Map<String, List<String>> keyColumns = new TreeMap<>();
    private final Set<String> columns = new TreeSet<>();
    private final Map<Skip, Long> skipped = new EnumMap<>(Skip.class);
    private boolean checked;

    public MappingExecutor(ExtractionMapping mapping) {
        this.mapping = mapping;
        this.documentId = mapping.documentId();
        this.sheet = mapping.options().sheet();
        List<TargetModel.Violation> violations = new ArrayList<>(mapping.validate());
        if (sheet != null && sheet.indexOf('\u0000') >= 0) {
            violations.add(new TargetModel.Violation("the sheet name holds a NUL character"));
        }
        mapping.entities().forEach((alias, entity) -> declare(alias, entity, violations));
        if (!violations.isEmpty()) {
            throw new InvalidExtractionMapping(mapping.id(), violations);
        }
        Stream.of(Skip.values()).forEach(reason -> skipped.put(reason, 0L));
    }

    /** What the run dropped, by reason, every reason present. */
    public Map<Skip, Long> skipped() {
        return Map.copyOf(skipped);
    }

    public List<Statement> statements(Row row) {
        requireColumns(row);
        Map<String, String> ids = identify(row);
        Map<String, Statement> statements = new LinkedHashMap<>();
        for (String alias : ids.keySet()) {
            List<Statement> candidate = statementsOf(alias, row, ids);
            if (candidate.isEmpty()) {
                count(Skip.ENTITY_EMPTY, alias, row.number());
            }
            candidate.forEach(statement -> statements.putIfAbsent(statement.id(), statement));
        }
        return List.copyOf(statements.values());
    }

    // Key columns are sorted and de-duplicated once here rather than per row, because the id a row
    // lands on depends on that order and a Map.copyOf does not carry one.
    private void declare(String alias, ExtractionMapping.EntityMapping entity,
                         List<TargetModel.Violation> violations) {
        keyColumns.put(alias, entity.keys().stream().distinct().sorted().toList());
        columns.addAll(entity.keys());
        if (entity.properties().isEmpty()) {
            violations.add(new TargetModel.Violation("entity '" + alias
                    + "' maps no property, so no row can produce a statement for it"));
        }
        entity.properties().forEach((name, property) -> {
            columns.addAll(property.columns());
            String where = "property '" + name + "' on entity '" + alias + "' ";
            if (property.literal() != null && property.literal().isBlank()) {
                violations.add(new TargetModel.Violation(where + "has a blank literal, which no row can store"));
            }
            if (property.format() != null) {
                try {
                    formats.declare(property.format());
                } catch (IllegalArgumentException unusable) {
                    violations.add(new TargetModel.Violation(where + "has an unusable format: "
                            + unusable.getMessage()));
                }
            }
        });
    }

    // Row.values pads a short row with empty strings, so a column the file does not have reads like a
    // blank cell: without this, one typo imports every row as nothing and reports it as a success. A
    // reader whose records carry their own names can legitimately omit a column further down, so only
    // the first row is worth failing on: after that a missing column is data, and is counted.
    private void requireColumns(Row row) {
        List<String> missing = columns.stream().filter(column -> !row.values().containsKey(column)).toList();
        if (!missing.isEmpty() && !checked) {
            throw new InvalidExtractionMapping(mapping.id(),
                    List.of(new TargetModel.Violation("the source has no column " + missing)));
        }
        missing.forEach(column -> count(Skip.CELL_MISSING, column, row.number()));
        checked = true;
    }

    private Map<String, String> identify(Row row) {
        Map<String, String> ids = new TreeMap<>();
        keyColumns.forEach((alias, keys) -> {
            List<String> values = keys.stream().map(column -> cell(row, column)).toList();
            if (values.stream().anyMatch(String::isEmpty)) {
                count(Skip.ENTITY_UNIDENTIFIED, alias, row.number());
            } else {
                ids.put(alias, id(mapping.entities().get(alias).type(), values));
            }
        });
        return ids;
    }

    // The key values in the order their column names sort, never reordered among themselves: two
    // mappings declaring the same keys in another order still land on one entity, while two people
    // whose given and family names are each other's do not. No column name and no alias, so two
    // mappings that call the same identifier differently agree. NUL-joined for the reason
    // Statement.id is: a cell can hold any printable character.
    private String id(String type, List<String> values) {
        return Hasher.SHA_384.hash(String.join("\u0000", mapping.model(), type, String.join("\u0000", values)));
    }

    private List<Statement> statementsOf(String alias, Row row, Map<String, String> ids) {
        ExtractionMapping.EntityMapping entity = mapping.entities().get(alias);
        String entityId = ids.get(alias);
        List<Statement> statements = new ArrayList<>();
        for (String property : entity.properties().keySet()) {
            ExtractionMapping.PropertyMapping mapped = entity.properties().get(property);
            if (mapped.literal() != null || mapped.entity() != null) {
                String given = mapped.literal() != null ? mapped.literal() : ids.get(mapped.entity());
                statement(statements, entityId, entity.type(), property, given, null, provenance(row, ""));
            } else if (mapped.join() != null) {
                statement(statements, entityId, entity.type(), property, mapped.columns().stream()
                        .map(column -> cell(row, column)).filter(cell -> !cell.isEmpty())
                        .collect(joining(mapped.join())), mapped.format(),
                        provenance(row, String.join(",", mapped.columns())));
            } else {
                for (String column : mapped.columns()) {
                    statement(statements, entityId, entity.type(), property, cell(row, column), mapped.format(),
                            provenance(row, column));
                }
            }
        }
        return statements;
    }

    private void statement(List<Statement> into, String entityId, String type, String property, String cell,
                           String format, Statement.Provenance provenance) {
        if (cell == null || cell.isEmpty()) {
            return;
        }
        String value = value(cell, format, provenance);
        Statement statement = Statement.of(mapping.model(), entityId, type, property, value, provenance);
        into.add(value.equals(cell) ? statement : statement.withOriginalValue(cell));
    }

    // Whatever the pattern cannot read is left exactly as it was rather than dropped or rewritten,
    // so one 'n/a' in a date column does not cost a run, and is counted so a whole column that never
    // converts cannot pass for a clean import. The log names the column, never the cell: an
    // unconvertible cell is document content, and DEBUG logs are not bound by project access rules.
    private String value(String cell, String format, Statement.Provenance provenance) {
        if (format == null) {
            return cell;
        }
        String iso = formats.iso(cell, format);
        if (iso != null) {
            return iso;
        }
        count(Skip.CELL_UNREADABLE, provenance.column(), provenance.rowNumber());
        return cell;
    }

    private Statement.Provenance provenance(Row row, String column) {
        return new Statement.Provenance(documentId, sheet, row.number(), column);
    }

    // The one place a cell enters, keys and values alike. A NUL would abort the run from inside
    // Statement's constructor, so it reads as blank. Row.clean is normalisation, not formatting: an
    // interior non-breaking space becomes a plain space with no originalValue recorded, the same way
    // surrounding whitespace is dropped unrecorded.
    private static String cell(Row row, String column) {
        String cell = row.values().getOrDefault(column, "");
        if (cell.indexOf('\u0000') >= 0) {
            return "";
        }
        return Row.clean(cell);
    }

    private void count(Skip reason, String what, long rowNumber) {
        skipped.merge(reason, 1L, Long::sum);
        LOGGER.debug("mapping '{}' row {}: {} '{}'", mapping.id(), rowNumber, reason, what);
    }
}
