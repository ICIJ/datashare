package org.icij.datashare.tabular;

import org.icij.datashare.model.Statement;
import org.icij.datashare.model.TargetModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

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

    /** What a run dropped, and why. ENTITY_ counts one entity of one row, CELL_UNREADABLE one cell,
     *  and CELL_MISSING one column, once: a column the source stops carrying is one structural fact,
     *  not one per row of a file that may hold millions. */
    public enum Skip { ENTITY_UNIDENTIFIED, ENTITY_EMPTY, CELL_UNREADABLE, CELL_MISSING }

    private final ExtractionMapping mapping;
    private final String documentId;
    private final String sheet;
    private final DateFormats formats = new DateFormats();
    private final Map<String, List<String>> keyColumns = new TreeMap<>();
    private final Set<String> columns = new TreeSet<>();
    private final Map<Skip, Long> skipped = new EnumMap<>(Skip.class);
    private final Set<String> absent = new TreeSet<>();
    private boolean checked;

    public MappingExecutor(ExtractionMapping mapping) {
        this.mapping = mapping;
        this.documentId = mapping.documentId();
        this.sheet = mapping.options().sheet();
        mapping.entities().forEach(this::declare);
        Stream.of(Skip.values()).forEach(reason -> skipped.put(reason, 0L));
    }

    /** What the run dropped, by reason, every reason present. */
    public Map<Skip, Long> skipped() {
        return Map.copyOf(skipped);
    }

    public List<Statement> statements(Row row) {
        requireColumns(row);
        Map<String, String> cells = new HashMap<>();
        columns.forEach(column -> cells.put(column, cell(row, column)));
        Map<String, String> ids = identify(cells, row.number());
        Map<String, Statement> statements = new LinkedHashMap<>();
        for (String alias : ids.keySet()) {
            List<Statement> candidate = statementsOf(alias, row, cells, ids);
            if (candidate.isEmpty()) {
                count(Skip.ENTITY_EMPTY, alias, row.number());
            }
            candidate.forEach(statement -> statements.putIfAbsent(statement.id(), statement));
        }
        return List.copyOf(statements.values());
    }

    // Key columns are de-duplicated once here rather than per row, because a column named twice
    // would otherwise hash its cell twice.
    private void declare(String alias, ExtractionMapping.EntityMapping entity) {
        keyColumns.put(alias, entity.keys().stream().distinct().toList());
        columns.addAll(entity.keys());
        entity.properties().values().forEach(property -> {
            columns.addAll(property.columns());
            if (property.format() != null) {
                formats.declare(property.format());
            }
        });
    }

    // Row.values pads a short row with empty strings, so a column the file does not have reads like a
    // blank cell: without this, one typo imports every row as nothing and reports it as a success. A
    // reader whose records carry their own names can legitimately omit a column further down, so only
    // the first row is worth failing on: after that a missing column is data, and is counted once,
    // since a field a whole file omits is one fact and not one per row.
    private void requireColumns(Row row) {
        if (row.values().keySet().containsAll(columns)) {
            checked = true;
            return;
        }
        List<String> missing = columns.stream().filter(column -> !row.values().containsKey(column)).toList();
        if (!checked) {
            throw new InvalidExtractionMapping(mapping.id(),
                    List.of(new TargetModel.Violation("the source has no column " + missing)));
        }
        missing.stream().filter(absent::add).forEach(column -> count(Skip.CELL_MISSING, column, row.number()));
    }

    private Map<String, String> identify(Map<String, String> cells, long rowNumber) {
        Map<String, String> ids = new TreeMap<>();
        keyColumns.forEach((alias, keys) -> {
            List<String> values = keys.stream().map(cells::get).toList();
            if (values.stream().anyMatch(String::isEmpty)) {
                count(Skip.ENTITY_UNIDENTIFIED, alias, rowNumber);
            } else {
                ids.put(alias, id(mapping.entities().get(alias).type(), values));
            }
        });
        return ids;
    }

    // The key values sorted among themselves, and no column name, no alias: two files naming the
    // same identifier differently still land on one entity. The price is that a swapped pair reads
    // as the same pair. NUL-joined for the reason Statement.id is: a cell can hold any printable
    // character.
    private String id(String type, List<String> values) {
        return Statement.DIGESTER.hash(String.join("\u0000", mapping.model(), type,
                String.join("\u0000", values.stream().sorted().toList())));
    }

    private List<Statement> statementsOf(String alias, Row row, Map<String, String> cells,
                                          Map<String, String> ids) {
        ExtractionMapping.EntityMapping entity = mapping.entities().get(alias);
        Filling filling = new Filling(ids.get(alias), entity.type());
        for (Map.Entry<String, ExtractionMapping.PropertyMapping> declared : entity.properties().entrySet()) {
            String property = declared.getKey();
            ExtractionMapping.PropertyMapping mapped = declared.getValue();
            if (mapped.literal() != null || mapped.entity() != null) {
                String given = mapped.literal() != null ? mapped.literal() : ids.get(mapped.entity());
                filling.fill(property, given, null, provenance(row, ""));
            } else if (mapped.join() != null) {
                filling.fill(property, mapped.columns().stream()
                        .map(cells::get).filter(cell -> !cell.isEmpty())
                        .collect(joining(mapped.join())), mapped.format(),
                        provenance(row, String.join(",", mapped.columns())));
            } else {
                for (String column : mapped.columns()) {
                    filling.fill(property, cells.get(column), mapped.format(), provenance(row, column));
                }
            }
        }
        return filling.statements;
    }

    // The entity id and type every statement of one entity carries, so filling a property passes
    // only what changes from one property to the next.
    private class Filling {
        private final List<Statement> statements = new ArrayList<>();
        private final String entityId;
        private final String type;

        private Filling(String entityId, String type) {
            this.entityId = entityId;
            this.type = type;
        }

        private void fill(String property, String cell, String format, Statement.Provenance provenance) {
            if (cell == null || cell.isEmpty()) {
                return;
            }
            String value = value(cell, format, provenance);
            Statement statement = Statement.of(mapping.model(), entityId, type, property, value, provenance);
            statements.add(value.equals(cell) ? statement : statement.withOriginalValue(cell));
        }
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
    // Statement's constructor, so it reads as blank, and is counted: unlike a genuinely blank cell,
    // it held content the run could not keep. Row.clean is normalisation, not formatting: an
    // interior non-breaking space becomes a plain space with no originalValue recorded, the same way
    // surrounding whitespace is dropped unrecorded.
    private String cell(Row row, String column) {
        String cell = row.values().getOrDefault(column, "");
        if (cell.indexOf('\u0000') >= 0) {
            count(Skip.CELL_UNREADABLE, column, row.number());
            return "";
        }
        return Row.clean(cell);
    }

    private void count(Skip reason, String what, long rowNumber) {
        skipped.merge(reason, 1L, Long::sum);
        LOGGER.debug("mapping '{}' row {}: {} '{}'", mapping.id(), rowNumber, reason, what);
    }
}
