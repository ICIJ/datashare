package org.icij.datashare.tabular;

import org.icij.datashare.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
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
public class StatementBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(StatementBuilder.class);

    /** What a run dropped, and why. ENTITY_ counts one entity of one row, CELL_UNREADABLE one cell,
     *  and CELL_MISSING one column, once: a column the source stops carrying is one structural fact,
     *  not one per row of a file that may hold millions. */
    public enum Skip {ENTITY_UNIDENTIFIED, ENTITY_EMPTY, CELL_UNREADABLE, CELL_MISSING}

    private final ExtractionMapping mapping;
    private final String documentId;
    private final String sheet;
    private final DateFormats formats = new DateFormats();
    private final Set<String> columns = new TreeSet<>();
    private final Map<Skip, Long> skipped = new EnumMap<>(Skip.class);
    private final Set<String> absent = new TreeSet<>();
    private final Set<String> seen = new TreeSet<>();

    /** {@code sheet} is the section of the document these rows come from, as the reader resolved it:
     *  a workbook's sheet name, the Tika fallback's table index, or empty for a format with one
     *  table. It is taken from the caller rather than read off the mapping's options, because the
     *  options hold what was asked for and the id needs what was read: "1" and "Sheet1" name one
     *  sheet and must hash alike, while two tables of one document must not. */
    public StatementBuilder(ExtractionMapping mapping, String sheet) {
        this.mapping = mapping;
        this.documentId = mapping.documentId();
        // The one string here the mapping never saw, so validate() cannot vouch for it: a workbook
        // naming a sheet with a NUL would otherwise reach Statement's constructor and abort the run
        // on its first row. What is left gets the cleaning every other string in a statement gets,
        // so a sheet named with a non-breaking space in one read and a plain one in the next does
        // not split a keyless entity in two.
        if (sheet != null && sheet.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("the sheet name holds a NUL character");
        }
        this.sheet = sheet == null ? null : Row.clean(sheet);
        mapping.requireValid();
        mapping.entities().forEach(this::declare);
        Stream.of(Skip.values()).forEach(reason -> skipped.put(reason, 0L));
    }

    /** The sheet as written in every statement's provenance, cleaned. */
    public String sheet() {
        return sheet;
    }

    /** What the run dropped, by reason, every reason present. */
    public Map<Skip, Long> skipped() {
        return Map.copyOf(skipped);
    }

    public List<Statement> statements(Row row) {
        requireColumns(row);
        Map<String, List<String>> cells = new HashMap<>();
        columns.forEach(column -> cells.put(column, cell(row, column)));
        Map<String, String> ids = entityIds(cells, row.number());
        Set<String> stored = storing(ids, cells);
        ids.keySet().stream().filter(alias -> !stored.contains(alias))
           .forEach(alias -> count(Skip.ENTITY_EMPTY, alias, row.number()));
        ids.keySet().retainAll(stored);
        Map<String, Statement> statements = new LinkedHashMap<>();
        for (String alias : ids.keySet()) {
            statementsOf(alias, row, cells, ids).forEach(
                    statement -> statements.putIfAbsent(statement.id(), statement));
        }
        return List.copyOf(statements.values());
    }

    // Formats compile without a guard: validate() already refused every pattern this mapping could
    // not run.
    private void declare(String alias, ExtractionMapping.EntityMapping entity) {
        columns.addAll(entity.keys());
        columns.addAll(entity.filters().keySet());
        if (entity.idColumn() != null) {
            columns.add(entity.idColumn());
        }
        entity.properties().values().forEach(property -> {
            columns.addAll(property.columns());
            if (property.dateFormat() != null) {
                formats.declare(property.dateFormat());
            }
        });
    }

    // Row.values pads a short row with empty strings, so a column the file does not have reads like
    // a blank cell: without this, one typo imports every row as nothing and reports it as a success.
    // A reader whose records carry their own names legitimately omits a column on any row, the first
    // one included, so the check is what the whole run saw rather than what row 1 had. A column
    // missing from a row is counted once, since a field a file omits is one fact, not one per row.
    private void requireColumns(Row row) {
        if (seen.size() < columns.size()) {
            row.values().keySet().stream().filter(columns::contains).forEach(seen::add);
        }
        columns.stream().filter(column -> !row.values().containsKey(column)).filter(absent::add)
               .forEach(column -> count(Skip.CELL_MISSING, column, row.number()));
    }

    /** The mapping typo this guard exists for, reported once the whole source has been read: a
     *  column no row carried is named here rather than on row 1, because row 1 of a mixed source is
     *  one record shape among several. The caller runs this inside the write transaction, so the
     *  retraction rolls back with it, exactly as a failure on row 1 did. */
    public void requireEveryColumnSeen() {
        List<String> never = columns.stream().filter(column -> !seen.contains(column)).toList();
        if (!never.isEmpty()) {
            throw new InvalidExtractionMapping(mapping.id(), List.of("the source has no column " + never));
        }
    }

    private Map<String, String> entityIds(Map<String, List<String>> cells, long rowNumber) {
        Map<String, String> ids = new TreeMap<>();
        for (String alias : new TreeSet<>(mapping.entities().keySet())) {
            String id = idOf(mapping.entities().get(alias), cells, alias, rowNumber);
            if (id != null) {
                ids.put(alias, id);
            }
        }
        return ids;
    }

    private String idOf(ExtractionMapping.EntityMapping entity, Map<String, List<String>> cells, String alias,
                        long rowNumber) {
        // Not a skip: an alias whose filters do not match was never meant to apply to this row, and
        // counting it would report every row of one schema as a loss for every other schema.
        if (!entity.filters().entrySet().stream()
                   .allMatch(filter -> cells.get(filter.getKey()).contains(filter.getValue()))) {
            return null;
        }
        if (entity.idColumn() != null) {
            String given = single(cells.get(entity.idColumn()));
            if (given == null || given.length() > Statement.MAX_ENTITY_ID_LENGTH) {
                count(Skip.ENTITY_UNIDENTIFIED, alias, rowNumber);
                return null;
            }
            return given;
        }
        if (entity.keys().isEmpty()) {
            return rowId(alias, entity.type(), rowNumber);
        }
        // A key column holding more than one value would have its values sorted into the hash,
        // picking an identity in silence; single() returns null and the entity is refused.
        List<String> values = entity.keys().stream().map(key -> single(cells.get(key))).toList();
        if (values.stream().anyMatch(value -> value == null)) {
            count(Skip.ENTITY_UNIDENTIFIED, alias, rowNumber);
            return null;
        }
        return id(entity.type(), entity.keyLiteral(), values);
    }

    // A keyless entity is row-scoped, each row its own record, so its id carries what locates the
    // row rather than key values. Re-reading the same file lands on the same ids; an edited file
    // shifts row numbers, which is what re-extracting a document is for. The alias is in the hash,
    // unlike the keyed recipe: with no key values to tell them apart, two keyless entities of one
    // type would otherwise be the same entity on every row, merging a row's buyer into its seller.
    private String rowId(String alias, String type, long rowNumber) {
        return Statement.DIGESTER.hash(
                String.join("\u0000", mapping.model(), type, alias, documentId, sheet == null ? "" : sheet,
                            String.valueOf(rowNumber)));
    }

    // The key values sorted among themselves, and no column name, no alias: two files naming the
    // same identifier differently still land on one entity. The price is that a swapped pair reads
    // as the same pair, and that two aliases of one type keyed on unrelated surrogate columns merge
    // when their values coincide, which is what the mapping's key literal is for. NUL-joined for
    // the reason Statement.id is: a cell can hold any printable character.
    private String id(String type, String keyLiteral, List<String> values) {
        return Statement.DIGESTER.hash(
                String.join("\u0000", mapping.model(), type, keyLiteral == null ? "" : keyLiteral,
                            String.join("\u0000", values.stream().sorted().toList())));
    }

    private List<Statement> statementsOf(String alias, Row row, Map<String, List<String>> cells,
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
                filling.fill(property, mapped.columns().stream().flatMap(column -> cells.get(column).stream())
                                             .filter(value -> !value.isEmpty()).collect(joining(mapped.join())),
                             mapped.dateFormat(), provenance(row, String.join(",", mapped.columns())));
            } else {
                for (String column : mapped.columns()) {
                    for (String value : cells.get(column)) {
                        filling.fill(property, value, mapped.dateFormat(), provenance(row, column));
                    }
                }
            }
        }
        return filling.statements;
    }

    // An entity produces a statement when a literal or a filled cell fills one of its properties, or
    // when it points at an entity that does: an edge holding nothing but its two endpoints is stored
    // on their strength. An entity that stores nothing sits in no index, so an edge naming its id
    // would record an endpoint that exists nowhere, and dropping that edge can in turn empty the
    // entity carrying it, which is why this settles rather than deciding in one pass.
    private Set<String> storing(Map<String, String> ids, Map<String, List<String>> cells) {
        Set<String> stored = new HashSet<>();
        for (boolean growing = true; growing; ) {
            growing = false;
            for (String alias : ids.keySet()) {
                if (!stored.contains(alias) && fills(alias, cells, stored)) {
                    stored.add(alias);
                    growing = true;
                }
            }
        }
        return stored;
    }

    private boolean fills(String alias, Map<String, List<String>> cells, Set<String> stored) {
        return mapping.entities().get(alias).properties().values().stream().anyMatch(
                mapped -> mapped.literal() != null || stored.contains(mapped.entity()) ||
                          mapped.columns().stream().anyMatch(column -> filled(cells.get(column))));
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

        private void fill(String property, String cell, String dateFormat, Statement.Provenance provenance) {
            if (cell == null || cell.isEmpty()) {
                return;
            }
            String value = dateValue(cell, dateFormat, provenance);
            Statement statement = Statement.of(mapping.model(), entityId, type, property, value, provenance);
            statements.add(value.equals(cell) ? statement : statement.withOriginalValue(cell));
        }
    }

    // Whatever the pattern cannot read is left exactly as it was rather than dropped or rewritten,
    // so one 'n/a' in a date column does not cost a run, and is counted so a whole column that never
    // converts cannot pass for a clean import. The log names the column, never the cell: an
    // unconvertible cell is document content, and DEBUG logs are not bound by project access rules.
    private String dateValue(String cell, String dateFormat, Statement.Provenance provenance) {
        if (dateFormat == null) {
            return cell;
        }
        String iso = formats.iso(cell, dateFormat);
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
    // Statement's constructor, so the value holding it is dropped and counted on its own: a column
    // whose other values are readable keeps them.
    private List<String> cell(Row row, String column) {
        List<String> raw = row.values().get(column);
        if (raw == null) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>(raw.size());
        for (String value : raw) {
            if (value.indexOf('\u0000') >= 0) {
                count(Skip.CELL_UNREADABLE, column, row.number());
            } else {
                cleaned.add(Row.clean(value));
            }
        }
        return List.copyOf(cleaned);
    }

    // A column a mapping reads as one value: blank, absent and multi-valued all read as "no value",
    // and every caller refuses on null rather than picking one.
    private static String single(List<String> values) {
        return values.size() == 1 && !values.get(0).isEmpty() ? values.get(0) : null;
    }

    private static boolean filled(List<String> values) {
        return values.stream().anyMatch(value -> !value.isEmpty());
    }

    private void count(Skip reason, String what, long rowNumber) {
        skipped.merge(reason, 1L, Long::sum);
        LOGGER.debug("mapping '{}' row {}: {} '{}'", mapping.id(), rowNumber, reason, what);
    }
}
