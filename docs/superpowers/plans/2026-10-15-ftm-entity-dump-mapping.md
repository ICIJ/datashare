# FtM entity dump mapping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One mapping file, run by the existing ENTITIES stage, builds entities and the edges between them out of a FollowTheMoney JSONL dump, with every edge resolving to the entities the file names.

**Architecture:** Four seams open, none of them in the write path. `Row` carries a list of values per column instead of one string, so `JsonRowSource` can map a JSON array onto a column and `StatementBuilder` can write one statement per value. `EntityMapping` gains `idColumn`, which takes the entity id verbatim from a column instead of hashing key values, so an edge's endpoint property maps straight from the column holding the target's FtM id. `EntityMapping` also gains `filters`, which restricts an alias to the rows whose schema column says so. The first-row "every declared column is present" check moves to end of run, inside the same transaction, so a mixed-schema dump is not refused on line 1.

**Tech Stack:** Java 21 (records, sealed-free), Jackson streaming (`JsonRowSource`), jOOQ + Postgres/SQLite (`JooqStatementRepository`), Elasticsearch (`EntitiesIndexRebuilder`), JUnit 4 + FEST assertions, Maven multi-module.

**Spec:** `docs/superpowers/specs/2026-10-15-ftm-entity-dump-mapping-design.md`

## Global Constraints

- Java module layout: `Row`, `StatementBuilder`, `ExtractionMapping` live in `datashare-api`; the four readers live in `datashare-app`; `JooqStatementRepository` lives in `datashare-db`.
- The id hash recipe in `StatementBuilder.id` and `StatementBuilder.rowId` is **not touched**. `StatementTest#test_the_id_digest_is_pinned` must keep passing unchanged.
- No change to `Statement`, `StatementRepository`, `EntitiesIndexRebuilder`, the ENTITIES stage, or `StructuredEntityExtractionResult` beyond the one stream wiring named in Task 6.
- A verbatim entity id is bounded by `Statement.MAX_ENTITY_ID_LENGTH` (512 characters, `datashare-api/src/main/java/org/icij/datashare/model/Statement.java:10`) and by `StructuredEntity.MAX_ID_BYTES` (512 UTF-8 bytes of the model-prefixed `_id`, which is what Elasticsearch caps).
- Every string reaching a statement is NUL-free; a NUL is refused at `validate()` for mapping strings and dropped-and-counted at read time for cell values.
- Out of scope, do not build: loading FtM entities without a mapping, a `required` option on a property, negative filters.
- Build command for `datashare-api` / `datashare-app` tests (no database needed):
  ```
  mvn -q -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app \
    -Dgpg.skip=true -Dmaven.gitcommitid.skip=true -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest=<ClassName> test
  ```
  Run it in the background and poll; a cold reactor build takes several minutes.
- Build command for `datashare-db` tests (Task 1 only; needs the devenv Postgres up):
  ```
  mvn -q -pl datashare-db -Dgpg.skip=true -Dmaven.gitcommitid.skip=true \
    -DpostgresBuildUri='jdbc:postgresql://postgres/dsbuild?user=dstest&password=test' \
    -Dtest=<ClassName> test
  ```
- Commit on the current feature branch (`t3code/0abf36b0`). Do not credit Claude in commit messages.
- Comments: default to zero. Write one only for a non-obvious *why*. The existing files in this area are heavily commented in that style — match it, do not narrate the diff.

## Review Focus

These are the input classes the spec implies but which no task's own happy-path test exercises. Each already has a test attached to the task that owns the code; this list is what a reviewer checks the tests against.

1. **A JSON array holding `null`** (`{"properties":{"name":[null,"ACME"]}}`). A scalar `null` already reads as `""`; inside an array it must read as a blank value that fills nothing, not as the literal string `"null"`. — Task 3.
2. **An array nested under an object** (`{"properties":{"name":["A","B"]}}`). This is the *only* shape an FtM dump has: the array sits under the `properties` object, not at the record root. A fix that only handles root-level arrays ships a reader that still dies on row 1. — Task 3.
3. **A filter naming a column the row does not carry.** After Task 6 a missing column is legal mid-file, so `cells.get(filterColumn)` must read as "no value, no match" rather than throwing a `NullPointerException` and killing the run. — Task 7.
4. **An `idColumn` value that is blank, multi-valued, NUL-bearing or over 512 characters.** Each refuses the entity and counts `ENTITY_UNIDENTIFIED`; none of them may reach `Statement`'s constructor, which aborts the run. — Task 5.
5. **Two values of one column cleaning to the same string** (`["ACME", "ACME "]`). The dump has these. They must collapse to one statement through the existing `putIfAbsent` on statement id, not produce a duplicate row the upsert then has to absorb. — Task 4.

---

## Task 1: Measure the write path before changing anything

The spec refuses to optimise on a guess. This task produces a number and a decision, and
changes no production code. Its result is written into this plan's "Measured" section below and
decides whether any of the three levers in the spec's Performance section gets built at all.

**Files:**
- Create: `datashare-db/src/test/java/org/icij/datashare/db/BenchStatementWrite.java`
- Read for reference: `datashare-db/src/test/java/org/icij/datashare/db/BenchBatchSearch.java` (the manual-bench pattern in this repo), `datashare-db/src/main/java/org/icij/datashare/db/JooqStatementRepository.java:78-110`

**Interfaces:**
- Consumes: `StatementRepository.replace(String projectId, String runId, String documentId, String sheet, Stream<Statement> statements)` returning `Replaced(int retracted, int written)`; `Statement.of(String model, String entityId, String type, String property, String value, Statement.Provenance provenance)`; `Statement.Provenance(String documentId, String sheet, long rowNumber, String column)`.
- Produces: nothing other tasks consume. A measurement.

- [x] **Step 1: Confirm the devenv Postgres is reachable**

Run: `psql 'postgresql://admin:admin@postgres/postgres' -c 'select version()'`

Expected: a version line. **Done 2026-10-15:** the host `postgres` resolves to 172.18.0.2 and the
only role on it is `admin`/`admin`, not the `dstest`/`test` the template assumes, so
`datashare-devenv.properties` was created from the template with `admin:admin` and the `postgres`
host. That file is gitignored (`.gitignore:33`) and the `dstest` and `dsbuild` databases already
carry the liquibase schema.

- [x] **Step 2: Write the benchmark**

Create `datashare-db/src/test/java/org/icij/datashare/db/BenchStatementWrite.java`:

```java
package org.icij.datashare.db;

import org.icij.datashare.EnvUtils;
import org.icij.datashare.model.Statement;
import org.icij.datashare.model.StatementRepository;
import org.jooq.SQLDialect;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.icij.datashare.db.Tables.STATEMENT;

/**
 * What a million-line FtM dump costs the write path as it stands. Not a unit test: it needs a real
 * Postgres and takes minutes, so it is named Bench rather than Test and surefire never picks it up.
 */
public class BenchStatementWrite {
    private static final Logger LOGGER = LoggerFactory.getLogger(BenchStatementWrite.class);
    private static final long ROWS = 1_000_000L;
    private static final int PROPERTIES = 6;

    @Rule public DbSetupRule dbRule = new DbSetupRule(
            EnvUtils.resolveUri("postgres", "jdbc:postgresql://postgres/dstest?user=dstest&password=test"));

    @Test
    public void bench_replace_of_a_million_row_dump() {
        StatementRepository repository = new JooqStatementRepository(dbRule.dataSource, SQLDialect.POSTGRES);
        long begin = System.currentTimeMillis();
        StatementRepository.Replaced replaced =
                repository.replace("prj", "run-1", "doc-1", "", statements());
        long elapsed = System.currentTimeMillis() - begin;

        LOGGER.info("BENCH wrote {} statements in {} ms ({} per second)", replaced.written(), elapsed,
                    replaced.written() * 1000L / Math.max(elapsed, 1));
        // fetchOne(String) returns a Record, not a Record1, so there is no value1() to call.
        LOGGER.info("BENCH table {}",
                    dbRule.dsl().fetchOne("select pg_size_pretty(pg_total_relation_size('statement'))").get(0));
        LOGGER.info("BENCH heap {}",
                    dbRule.dsl().fetchOne("select pg_size_pretty(pg_relation_size('statement'))").get(0));
        LOGGER.info("BENCH rows {}", dbRule.dsl().selectCount().from(STATEMENT).fetchOne().value1());
        dbRule.shutdown();
    }

    private static Stream<Statement> statements() {
        return LongStream.range(0, ROWS).boxed().flatMap(row -> LongStream.range(0, PROPERTIES).mapToObj(
                property -> Statement.of("ftm", "entity-" + row, "Person", "name",
                                         "value-" + row + "-" + property,
                                         new Statement.Provenance("doc-1", "", row + 1, "column-" + property))));
    }
}
```

- [x] **Step 3: Run it and record the numbers**

Run (background, then poll):
```
mvn -pl datashare-db -Dgpg.skip=true -Dmaven.gitcommitid.skip=true \
  -DpostgresBuildUri='jdbc:postgresql://postgres/dsbuild?user=dstest&password=test' \
  -Dtest=BenchStatementWrite test
```
Expected: it completes and logs four lines. If `Statement.of` refuses a value the generator
produces, fix the generator, not `Statement`.

Record in this file, in the Measured table at the bottom: wall time, statements per second, total
relation size, heap size.

- [x] **Step 4: Measure the index rebuild alongside it**

Run the existing end-to-end path once against the same data so the two costs are not confused.
If bringing up Elasticsearch for this is more than a few minutes of work, skip it and write
"not measured" in the Measured table's rebuild row instead — the write is the number this task exists
for.

- [x] **Step 5: Decide, in writing**

Write one of these two lines on the Measured section's Decision line:

- `Decision: no performance work. The write landed in <N> minutes, comfortably inside the task's P1D activity timeout.`
- `Decision: lever <1|2|3> needed. The write took <N>, which is <reason>.`

If a lever is needed, **stop and raise it with the human partner before building it**. The three
levers (`reWriteBatchedInserts`, commit-per-chunk, `COPY` into a temp table) each trade away a
guarantee the spec names, and which trade is acceptable is not this plan's call.

- [ ] **Step 6: Commit**

```bash
git add datashare-db/src/test/java/org/icij/datashare/db/BenchStatementWrite.java \
        docs/superpowers/plans/2026-10-15-ftm-entity-dump-mapping.md
git commit -m "test(db): measure what a million-row dump costs the statement write path"
```

---

## Task 2: `Row` carries several values per column

A pure type change. `Row.values` becomes `Map<String, List<String>>`, every reader wraps its one
cell in a one-element list, and `StatementBuilder` reads the sole value back out. Behaviour is
identical at the end of this task — `RowSourceEquivalenceTest` and the whole of
`StatementBuilderTest` keep passing, with only the shape of their fixtures changed.

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/tabular/Row.java` (the record header, `values(headers, cells)`, `values(headers, cells, number)`)
- Modify: `datashare-api/src/main/java/org/icij/datashare/tabular/StatementBuilder.java:78-92` (`statements`), `:122-139` (`entityIds`), `:195-209` (`storing`, `fills`), `:256-266` (`cell`)
- Modify: `datashare-app/src/main/java/org/icij/datashare/tabular/JsonRowSource.java:107-121` (the `next()` body's `values` map)
- Verify unchanged in behaviour: `datashare-app/src/main/java/org/icij/datashare/tabular/DelimitedRowSource.java:152`, `WorkbookRowSource.java:101`, `TikaTableRowSource.java:105` — these call `Row.values(...)` and need no edit at all
- Test: `datashare-api/src/test/java/org/icij/datashare/tabular/RowTest.java:56,61,66`
- Test: `datashare-api/src/test/java/org/icij/datashare/tabular/StatementBuilderTest.java:59,223,819,833,834`
- Test: `datashare-app/src/test/java/org/icij/datashare/tabular/RowSourceEquivalenceTest.java:21-23,46`
- Test: `datashare-app/src/test/java/org/icij/datashare/tabular/JsonRowSourceTest.java` (every `values().get(...)` assertion)
- Test: `datashare-db/src/test/java/org/icij/datashare/db/JooqStatementRepositoryTest.java:160-167`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `record Row(long number, Map<String, List<String>> values)`
  - `static Map<String, List<String>> Row.values(List<String> headers, List<String> cells)`
  - `static Map<String, List<String>> Row.values(List<String> headers, List<String> cells, long number)`
  - `Row.clean(String)` and `Row.blank(String)` unchanged, still per value.
  - In `StatementBuilder`: `private List<String> cell(Row row, String column)` returning cleaned values, empty list when the column is absent from the row.

- [ ] **Step 1: Write the failing test**

Add to `datashare-api/src/test/java/org/icij/datashare/tabular/RowTest.java`:

```java
    @Test
    public void test_a_cell_becomes_a_one_element_list() {
        assertThat(Row.values(List.of("a", "b"), List.of("1", "2")))
                .isEqualTo(Map.of("a", List.of("1"), "b", List.of("2")));
    }

    @Test
    public void test_a_short_row_pads_with_a_blank_value() {
        assertThat(Row.values(List.of("a", "b"), List.of("1")))
                .isEqualTo(Map.of("a", List.of("1"), "b", List.of("")));
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```
mvn -q -pl commons-test,datashare-api -Dgpg.skip=true -Dmaven.gitcommitid.skip=true \
  -Dsurefire.failIfNoSpecifiedTests=false -Dtest=RowTest test
```
Expected: compile failure, `incompatible types: Map<String,String> cannot be converted to Map<String,List<String>>`. A compile failure is a legitimate RED here: the type does not exist yet.

- [ ] **Step 3: Change `Row`**

In `datashare-api/src/main/java/org/icij/datashare/tabular/Row.java`, change the record header
and the two `values` factories. The javadoc on the record stays, with one sentence added:

```java
/**
 * One data row. {@code number} is the 1-based ordinal of the emitted row, not the row's position in
 * the file: the header row and the rows a reader skips, an entirely blank one for instance, consume
 * no number.
 *
 * <p>A column holds a list rather than a string because a json record's property may be an array. A
 * tabular cell holds one value and so a one-element list; nothing downstream has to tell the two
 * apart.
 */
public record Row(long number, Map<String, List<String>> values) {
```

and:

```java
    public static Map<String, List<String>> values(List<String> headers, List<String> cells) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (int column = 0; column < headers.size(); column++) {
            if (headers.get(column) != null) {
                values.put(headers.get(column),
                           List.of(column < cells.size() ? clean(cells.get(column)) : ""));
            }
        }
        return Collections.unmodifiableMap(values);
    }

    public static Map<String, List<String>> values(List<String> headers, List<String> cells, long number) {
        if (!cells.stream().skip(headers.size()).allMatch(Row::blank)) {
            throw new IllegalArgumentException(
                    "row " + number + " has " + cells.size() + " fields but the header declares " + headers.size());
        }
        return values(headers, cells);
    }
```

- [ ] **Step 4: Adapt `JsonRowSource` to the new type, still refusing arrays**

In `datashare-app/src/main/java/org/icij/datashare/tabular/JsonRowSource.java`, change the two
places the map is typed. The array refusal stays exactly as it is — Task 3 removes it.

```java
                Map<String, List<String>> values = new LinkedHashMap<>();
```
and in `flatten`:
```java
    private static void flatten(String prefix, JsonNode node, Map<String, List<String>> values) {
        node.properties().forEach(field -> {
            String column = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
            JsonNode value = field.getValue();
            if (value.isObject()) {
                flatten(column, value, values);
            } else if (value.isArray()) {
                throw new IllegalArgumentException(
                        "column " + column + " holds an array, which no table cell can carry");
            } else {
                values.put(column, List.of(value.isNull() ? "" : value.asText()));
            }
        });
    }
```

- [ ] **Step 5: Adapt `StatementBuilder` to the new type, behaviour unchanged**

In `datashare-api/src/main/java/org/icij/datashare/tabular/StatementBuilder.java`:

`cell` becomes:

```java
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
```

Add one private helper next to it:

```java
    // A column a mapping reads as one value: blank, absent and multi-valued all read as "no value",
    // and every caller refuses on null rather than picking one.
    private static String single(List<String> values) {
        return values.size() == 1 && !values.get(0).isEmpty() ? values.get(0) : null;
    }

    private static boolean filled(List<String> values) {
        return values.stream().anyMatch(value -> !value.isEmpty());
    }
```

Retype the three maps and adapt their readers:

```java
    public List<Statement> statements(Row row) {
        requireColumns(row);
        Map<String, List<String>> cells = new HashMap<>();
        columns.forEach(column -> cells.put(column, cell(row, column)));
        ...
```

```java
    private Map<String, String> entityIds(Map<String, List<String>> cells, long rowNumber) {
        Map<String, String> ids = new TreeMap<>();
        keyColumns.forEach((alias, keys) -> {
            ExtractionMapping.EntityMapping entity = mapping.entities().get(alias);
            if (keys.isEmpty()) {
                ids.put(alias, rowId(alias, entity.type(), rowNumber));
                return;
            }
            List<String> values = keys.stream().map(key -> single(cells.get(key))).toList();
            if (values.stream().anyMatch(value -> value == null)) {
                count(Skip.ENTITY_UNIDENTIFIED, alias, rowNumber);
            } else {
                ids.put(alias, id(entity.type(), entity.keyLiteral(), values));
            }
        });
        return ids;
    }
```

```java
    private List<Statement> statementsOf(String alias, Row row, Map<String, List<String>> cells,
                                         Map<String, String> ids) {
        ...
            } else if (mapped.join() != null) {
                filling.fill(property, mapped.columns().stream().map(column -> single(cells.get(column)))
                                             .filter(value -> value != null)
                                             .collect(joining(mapped.join())), mapped.dateFormat(),
                             provenance(row, String.join(",", mapped.columns())));
            } else {
                for (String column : mapped.columns()) {
                    filling.fill(property, single(cells.get(column)), mapped.dateFormat(), provenance(row, column));
                }
            }
        ...
    }
```

```java
    private Set<String> storing(Map<String, String> ids, Map<String, List<String>> cells) { ... }

    private boolean fills(String alias, Map<String, List<String>> cells, Set<String> stored) {
        return mapping.entities().get(alias).properties().values().stream().anyMatch(
                mapped -> mapped.literal() != null || stored.contains(mapped.entity()) ||
                          mapped.columns().stream().anyMatch(column -> filled(cells.get(column))));
    }
```

Add `import java.util.ArrayList;` if it is not already there (it is, for `Filling`).

- [ ] **Step 6: Adapt the test fixtures**

`RowTest.java`, lines 56, 61, 66 — wrap the expectations:
```java
        assertThat(Row.values(List.of("a"), List.of("1", " "), 2L)).isEqualTo(Map.of("a", List.of("1")));
        assertThat(Row.values(List.of("a"), List.of("1 "))).isEqualTo(Map.of("a", List.of("1")));
        assertThat(Row.values(List.of("a"), List.of("AB​123"))).isEqualTo(Map.of("a", List.of("AB123")));
```

`StatementBuilderTest.java` — change the helper at line 59 and add a numbered one, then replace
the four direct `new Row(8L, Map.of(...))` / `new Row(9L, ...)` calls at lines 223, 819, 833, 834
with `row(8L, Map.of(...))` and `row(9L, Map.of(...))`:
```java
    private static Row row(Map<String, String> values) {
        return row(7L, values);
    }

    private static Row row(long number, Map<String, String> values) {
        Map<String, List<String>> cells = new LinkedHashMap<>();
        values.forEach((column, value) -> cells.put(column, List.of(value)));
        return new Row(number, cells);
    }
```
Add `import java.util.LinkedHashMap;`.

`RowSourceEquivalenceTest.java` — change `EXPECTED` and keep the assertion as it is:
```java
    private static final List<Map<String, List<String>>> EXPECTED = List.of(
            Map.of("id", List.of("1"), "name", List.of("ACME"), "country", List.of("FR")),
            Map.of("id", List.of("2"), "name", List.of("Globex"), "country", List.of("US")));
```
This is the test the spec names as the guard: it must keep passing with its meaning unchanged,
which it does — the same four readers still agree on the same data.

`JsonRowSourceTest.java` — every `rows.get(n).values().get("x")` assertion becomes
`.isEqualTo(List.of("ACME"))` and so on; the `isNull()` assertions stay `isNull()`.

`JooqStatementRepositoryTest.java:160-167` — the local `cells` map becomes
`Map<String, List<String>>` with each value wrapped in `List.of(...)`.

- [ ] **Step 7: Run every touched test and verify it passes**

Run (background, poll):
```
mvn -q -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app \
  -Dgpg.skip=true -Dmaven.gitcommitid.skip=true -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='RowTest,StatementBuilderTest,StatementBuilderCsvTest,RowSourceEquivalenceTest,JsonRowSourceTest,DelimitedRowSourceTest,WorkbookRowSourceTest,TikaTableRowSourceTest,TabularRowReaderTest,StructuredEntityExtractionTaskTest,StatementTest' test
```
Expected: PASS, all of them. `StatementTest#test_the_id_digest_is_pinned` passing is the proof
that the id recipe was not touched.

- [ ] **Step 8: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/tabular/Row.java \
        datashare-api/src/main/java/org/icij/datashare/tabular/StatementBuilder.java \
        datashare-app/src/main/java/org/icij/datashare/tabular/JsonRowSource.java \
        datashare-api/src/test/java/org/icij/datashare/tabular/RowTest.java \
        datashare-api/src/test/java/org/icij/datashare/tabular/StatementBuilderTest.java \
        datashare-app/src/test/java/org/icij/datashare/tabular/RowSourceEquivalenceTest.java \
        datashare-app/src/test/java/org/icij/datashare/tabular/JsonRowSourceTest.java \
        datashare-db/src/test/java/org/icij/datashare/db/JooqStatementRepositoryTest.java
git commit -m "refactor(tabular): let a row column carry several values"
```

---

## Task 3: a JSON array fills a column with its values

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tabular/JsonRowSource.java` (`flatten`, and the class javadoc's array sentence)
- Test: `datashare-app/src/test/java/org/icij/datashare/tabular/JsonRowSourceTest.java`

**Interfaces:**
- Consumes: `record Row(long number, Map<String, List<String>> values)` from Task 2.
- Produces: no new signature. A `flatten` that maps a scalar array onto the column's list, still
  refuses an array holding an object or an array, and maps an empty array onto an empty list.

- [ ] **Step 1: Write the failing tests**

Replace `test_an_array_value_is_refused_naming_its_row_and_column` in
`datashare-app/src/test/java/org/icij/datashare/tabular/JsonRowSourceTest.java` with these, and
add the rest:

```java
    @Test
    public void test_a_scalar_array_fills_the_column_with_its_values() throws Exception {
        List<Row> rows = read("[{\"id\":1,\"tags\":[\"a\",\"b\"]}]");

        assertThat(rows.get(0).values().get("tags")).isEqualTo(List.of("a", "b"));
    }

    @Test
    public void test_an_array_nested_under_an_object_fills_its_dotted_column() throws Exception {
        List<Row> rows = read("[{\"id\":\"e1\",\"properties\":{\"name\":[\"ACME\",\"Acme SA\"]}}]");

        assertThat(rows.get(0).values().get("properties.name")).isEqualTo(List.of("ACME", "Acme SA"));
    }

    @Test
    public void test_an_empty_array_fills_nothing() throws Exception {
        List<Row> rows = read("[{\"id\":1,\"tags\":[]}]");

        assertThat(rows.get(0).values().get("tags")).isEqualTo(List.of());
    }

    @Test
    public void test_a_null_inside_an_array_reads_as_a_blank_value() throws Exception {
        List<Row> rows = read("[{\"id\":1,\"tags\":[null,\"a\"]}]");

        assertThat(rows.get(0).values().get("tags")).isEqualTo(List.of("", "a"));
    }

    @Test
    public void test_an_array_of_objects_is_still_refused_naming_its_row_and_column() throws Exception {
        Map<String, String> columnByContent = Map.of(
                "[{\"id\":1,\"kids\":[{\"n\":1}]}]", "kids",
                "[{\"id\":1,\"pairs\":[[1,2]]}]", "pairs",
                "[{\"id\":1,\"addr\":{\"kids\":[{\"n\":1}]}}]", "addr.kids");
        for (Map.Entry<String, String> arrayValue : columnByContent.entrySet()) {
            try {
                read(arrayValue.getKey());
                fail("a member with no column name of its own cannot be mapped");
            } catch (IllegalArgumentException failure) {
                assertThat(failure.getMessage()).contains("row 1");
                assertThat(failure.getMessage()).contains(arrayValue.getValue());
            }
        }
    }
```

Add `import java.util.List;` if absent.

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```
mvn -q -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app \
  -Dgpg.skip=true -Dmaven.gitcommitid.skip=true -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=JsonRowSourceTest test
```
Expected: FAIL. The four new array tests fail with `column tags holds an array, which no table
cell can carry`; `test_an_array_of_objects_is_still_refused...` passes already.

- [ ] **Step 3: Map a scalar array onto the column's values**

In `JsonRowSource.java`, replace `flatten` and its comment:

```java
    // An array's members are the column's values: that is what the ftm dumps this reader exists for
    // put in every property. A member that is itself an object or an array has no column name to be
    // mapped under, so it is refused rather than flattened into one the mapping cannot address.
    private static void flatten(String prefix, JsonNode node, Map<String, List<String>> values) {
        node.properties().forEach(field -> {
            String column = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
            JsonNode value = field.getValue();
            if (value.isObject()) {
                flatten(column, value, values);
            } else if (value.isArray()) {
                values.put(column, members(column, value));
            } else {
                values.put(column, List.of(text(value)));
            }
        });
    }

    private static List<String> members(String column, JsonNode array) {
        List<String> members = new ArrayList<>(array.size());
        for (JsonNode member : array) {
            if (member.isContainerNode()) {
                throw new IllegalArgumentException(
                        "column " + column + " holds an array of " + member.getNodeType() +
                        ", whose members have no column name to be mapped under");
            }
            members.add(text(member));
        }
        return List.copyOf(members);
    }

    private static String text(JsonNode value) {
        return value.isNull() ? "" : value.asText();
    }
```

Add `import java.util.ArrayList;` and `import java.util.List;`.

Update the class javadoc's last sentence, which still claims a duplicate key collides silently and
says nothing about arrays — leave the duplicate-key sentence, nothing changed there.

- [ ] **Step 4: Run the tests to verify they pass**

Run:
```
mvn -q -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app \
  -Dgpg.skip=true -Dmaven.gitcommitid.skip=true -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='JsonRowSourceTest,RowSourceEquivalenceTest,TabularRowReaderTest' test
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tabular/JsonRowSource.java \
        datashare-app/src/test/java/org/icij/datashare/tabular/JsonRowSourceTest.java
git commit -m "feat(tabular): read a json array as a column's several values"
```

---

## Task 4: `StatementBuilder` writes one statement per value

Task 2 made the type multi-valued but kept every reader single-valued (`single(...)` everywhere).
This task makes the builder actually use the extra values.

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/tabular/StatementBuilder.java` (`statementsOf`, `entityIds`)
- Test: `datashare-api/src/test/java/org/icij/datashare/tabular/StatementBuilderTest.java`

**Interfaces:**
- Consumes: `cell`, `single`, `filled` from Task 2.
- Produces: no new public signature. `statements(Row)` returns one statement per distinct value of
  each mapped column; `Skip.ENTITY_UNIDENTIFIED` now also counts a multi-valued key column.

- [ ] **Step 1: Write the failing tests**

Add to `datashare-api/src/test/java/org/icij/datashare/tabular/StatementBuilderTest.java`. Add a
multi-value row helper next to the existing one:

```java
    private static Row multi(Map<String, List<String>> values) {
        return new Row(7L, values);
    }

    @Test
    public void test_a_multi_valued_column_writes_one_statement_per_value() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", keyed("Company", null, List.of("id"), Map.of("name", column("names"))))), "");

        List<Statement> statements = builder.statements(
                multi(Map.of("id", List.of("e1"), "names", List.of("ACME", "Acme SA"))));

        assertThat(statements).hasSize(2);
        assertThat(statements.stream().map(Statement::value).toList()).isEqualTo(List.of("ACME", "Acme SA"));
        assertThat(statements.stream().map(Statement::entityId).distinct().toList()).hasSize(1);
    }

    @Test
    public void test_two_values_cleaning_to_the_same_string_write_one_statement() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", keyed("Company", null, List.of("id"), Map.of("name", column("names"))))), "");

        List<Statement> statements = builder.statements(
                multi(Map.of("id", List.of("e1"), "names", List.of("ACME", "ACME "))));

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).value()).isEqualTo("ACME");
    }

    @Test
    public void test_a_blank_value_inside_a_column_writes_nothing() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", keyed("Company", null, List.of("id"), Map.of("name", column("names"))))), "");

        List<Statement> statements = builder.statements(
                multi(Map.of("id", List.of("e1"), "names", List.of("", "ACME"))));

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).value()).isEqualTo("ACME");
    }

    @Test
    public void test_join_runs_across_columns_then_across_each_column_values() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", keyed("Company", null, List.of("id"),
                                 Map.of("name", joined(List.of("first", "second"), " "))))), "");

        List<Statement> statements = builder.statements(multi(Map.of(
                "id", List.of("e1"), "first", List.of("a", "b"), "second", List.of("c", ""))));

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).value()).isEqualTo("a b c");
    }

    @Test
    public void test_a_multi_valued_key_column_refuses_the_entity() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", keyed("Company", null, List.of("id"), Map.of("name", column("names"))))), "");

        List<Statement> statements = builder.statements(
                multi(Map.of("id", List.of("e1", "e2"), "names", List.of("ACME"))));

        assertThat(statements).isEmpty();
        assertThat(builder.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(1L);
    }

    @Test
    public void test_a_date_format_applies_to_every_value_of_a_column() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", keyed("Person", null, List.of("id"),
                                Map.of("birthDate", formatted("dates", "dd/MM/yyyy"))))), "");

        List<Statement> statements = builder.statements(
                multi(Map.of("id", List.of("e1"), "dates", List.of("01/01/1970", "02/02/1980"))));

        assertThat(statements.stream().map(Statement::value).toList())
                .isEqualTo(List.of("1970-01-01", "1980-02-02"));
    }

    @Test
    public void test_a_nul_in_one_value_drops_that_value_only() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", keyed("Company", null, List.of("id"), Map.of("name", column("names"))))), "");

        List<Statement> statements = builder.statements(
                multi(Map.of("id", List.of("e1"), "names", List.of("A\u0000B", "ACME"))));

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).value()).isEqualTo("ACME");
        assertThat(builder.skipped().get(CELL_UNREADABLE)).isEqualTo(1L);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```
mvn -q -pl commons-test,datashare-api -Dgpg.skip=true -Dmaven.gitcommitid.skip=true \
  -Dsurefire.failIfNoSpecifiedTests=false -Dtest=StatementBuilderTest test
```
Expected: FAIL. `test_a_multi_valued_column_writes_one_statement_per_value` expects 2 and gets 0
(`single` returns null for a two-element list). `test_a_nul_in_one_value_drops_that_value_only`
already passes, from Task 2's `cell`.

- [ ] **Step 3: Write one statement per value**

In `StatementBuilder.statementsOf`, replace the `join` branch and the `columns` branch:

```java
            } else if (mapped.join() != null) {
                filling.fill(property,
                             mapped.columns().stream().flatMap(column -> cells.get(column).stream())
                                   .filter(value -> !value.isEmpty()).collect(joining(mapped.join())),
                             mapped.dateFormat(), provenance(row, String.join(",", mapped.columns())));
            } else {
                for (String column : mapped.columns()) {
                    for (String value : cells.get(column)) {
                        filling.fill(property, value, mapped.dateFormat(), provenance(row, column));
                    }
                }
            }
```

`entityIds` keeps `single(...)` for key columns — that is what refuses a multi-valued key. Add one
comment above the `single` helper's use there if it is not already obvious:

```java
            // A key column holding more than one value would have its values sorted into the hash,
            // picking an identity in silence; single() returns null and the entity is refused.
            List<String> values = keys.stream().map(key -> single(cells.get(key))).toList();
```

- [ ] **Step 4: Run the tests to verify they pass**

Run:
```
mvn -q -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app \
  -Dgpg.skip=true -Dmaven.gitcommitid.skip=true -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='StatementBuilderTest,StatementBuilderCsvTest,StatementTest' test
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/tabular/StatementBuilder.java \
        datashare-api/src/test/java/org/icij/datashare/tabular/StatementBuilderTest.java
git commit -m "feat(tabular): write one statement per value of a multi-valued column"
```

---

## Task 5: `idColumn` takes the entity id verbatim from a column

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/tabular/ExtractionMapping.java` (`EntityMapping` record and its constructors, `validate()`)
- Modify: `datashare-api/src/main/java/org/icij/datashare/tabular/StatementBuilder.java` (`declare`, `entityIds`)
- Test: `datashare-api/src/test/java/org/icij/datashare/tabular/ExtractionMappingTest.java`
- Test: `datashare-api/src/test/java/org/icij/datashare/tabular/StatementBuilderTest.java`

**Interfaces:**
- Consumes: `single`, `cell`, `Skip.ENTITY_UNIDENTIFIED` from Tasks 2 and 4.
- Produces:
  - `public record EntityMapping(String type, String keyLiteral, List<String> keys, String idColumn, Map<String, PropertyMapping> properties)` — the canonical constructor Jackson binds, in that component order.
  - Convenience constructors kept for existing callers and tests:
    - `EntityMapping(String type, List<String> keys, Map<String, PropertyMapping> properties)`
    - `EntityMapping(String type, String keyLiteral, List<String> keys, Map<String, PropertyMapping> properties)`
  - `String EntityMapping.idColumn()` — null when the entity is keyed or row-scoped.
  - Task 7 widens this record again with `filters`; it will add its component **after** `idColumn` and before `properties`, and update these same convenience constructors.

- [ ] **Step 1: Write the failing tests**

Add to `datashare-api/src/test/java/org/icij/datashare/tabular/StatementBuilderTest.java`:

```java
    private static ExtractionMapping.EntityMapping identified(String type, String idColumn,
                                                              Map<String, ExtractionMapping.PropertyMapping> properties) {
        return new ExtractionMapping.EntityMapping(type, null, List.of(), idColumn, properties);
    }

    @Test
    public void test_an_id_column_becomes_the_entity_id_verbatim() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", identified("Company", "id", Map.of("name", column("name"))))), "");

        List<Statement> statements = builder.statements(row(Map.of("id", "a1b2c3", "name", "ACME")));

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).entityId()).isEqualTo("a1b2c3");
    }

    @Test
    public void test_an_edge_resolves_to_the_ids_its_columns_hold() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "ownership", identified("Ownership", "id",
                                        Map.of("owner", column("owner"), "asset", column("asset"))))), "");

        List<Statement> statements = builder.statements(
                row(Map.of("id", "edge-1", "owner", "person-1", "asset", "company-1")));

        assertThat(statements.stream().map(Statement::value).sorted().toList())
                .isEqualTo(List.of("company-1", "person-1"));
        assertThat(statements.stream().map(Statement::entityId).distinct().toList()).isEqualTo(List.of("edge-1"));
    }

    @Test
    public void test_a_blank_id_column_refuses_the_entity() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", identified("Company", "id", Map.of("name", column("name"))))), "");

        assertThat(builder.statements(row(Map.of("id", "", "name", "ACME")))).isEmpty();
        assertThat(builder.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(1L);
    }

    @Test
    public void test_a_multi_valued_id_column_refuses_the_entity() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", identified("Company", "id", Map.of("name", column("name"))))), "");

        assertThat(builder.statements(multi(Map.of("id", List.of("a", "b"), "name", List.of("ACME"))))).isEmpty();
        assertThat(builder.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(1L);
    }

    @Test
    public void test_a_nul_bearing_id_column_refuses_the_entity() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", identified("Company", "id", Map.of("name", column("name"))))), "");

        assertThat(builder.statements(row(Map.of("id", "a\u0000b", "name", "ACME")))).isEmpty();
        assertThat(builder.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(1L);
        assertThat(builder.skipped().get(CELL_UNREADABLE)).isEqualTo(1L);
    }

    @Test
    public void test_an_id_longer_than_the_column_allows_refuses_the_entity() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", identified("Company", "id", Map.of("name", column("name"))))), "");

        assertThat(builder.statements(row(Map.of("id", "x".repeat(Statement.MAX_ENTITY_ID_LENGTH + 1),
                                                 "name", "ACME")))).isEmpty();
        assertThat(builder.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(1L);
    }

    @Test
    public void test_an_id_of_exactly_the_maximum_length_is_kept() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", identified("Company", "id", Map.of("name", column("name"))))), "");
        String longest = "x".repeat(Statement.MAX_ENTITY_ID_LENGTH);

        assertThat(builder.statements(row(Map.of("id", longest, "name", "ACME"))).get(0).entityId())
                .isEqualTo(longest);
    }
```

Add to `datashare-api/src/test/java/org/icij/datashare/tabular/ExtractionMappingTest.java`, using
that file's existing `mapping(model, entities)` and `column(name)` helpers:

```java
    private static ExtractionMapping.EntityMapping identified(String keyLiteral, List<String> keys, String idColumn) {
        return new ExtractionMapping.EntityMapping("Person", keyLiteral, keys, idColumn,
                Map.of("name", column("full_name")));
    }

    @Test
    public void test_an_id_column_and_keys_together_are_refused() {
        assertThat(mapping("ftm", Map.of("member", identified(null, List.of("passport"), "id"))).validate().toString())
                .contains("entity 'member' has both an id column and a key, and an id is read one way only");
    }

    @Test
    public void test_an_id_column_and_a_key_literal_together_are_refused() {
        assertThat(mapping("ftm", Map.of("member", identified("registry", List.of(), "id"))).validate().toString())
                .contains("entity 'member' has a key literal but no key, and a row-scoped id carries no literal");
    }

    @Test
    public void test_a_blank_id_column_name_is_refused() {
        assertThat(mapping("ftm", Map.of("member", identified(null, List.of(), "  "))).validate().toString())
                .contains("entity 'member' has a blank id column name, which no header can match");
    }

    @Test
    public void test_a_nul_in_an_id_column_name_is_refused() {
        assertThat(mapping("ftm", Map.of("member", identified(null, List.of(), "i\u0000d"))).validate().toString())
                .contains("the id column name of entity 'member' holds a NUL character");
    }

    @Test
    public void test_an_id_column_on_its_own_is_valid() {
        assertThat(mapping("ftm", Map.of("member", identified(null, List.of(), "id"))).validate()).isEmpty();
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```
mvn -q -pl commons-test,datashare-api -Dgpg.skip=true -Dmaven.gitcommitid.skip=true \
  -Dsurefire.failIfNoSpecifiedTests=false -Dtest='StatementBuilderTest,ExtractionMappingTest' test
```
Expected: compile failure — `EntityMapping` has no five-argument constructor. That is the RED.

- [ ] **Step 3: Add `idColumn` to `EntityMapping`**

In `datashare-api/src/main/java/org/icij/datashare/tabular/ExtractionMapping.java`:

```java
    public record EntityMapping(String type, String keyLiteral, List<String> keys, String idColumn,
                                Map<String, PropertyMapping> properties) {
        public EntityMapping(String type, List<String> keys, Map<String, PropertyMapping> properties) {
            this(type, null, keys, null, properties);
        }

        public EntityMapping(String type, String keyLiteral, List<String> keys,
                             Map<String, PropertyMapping> properties) {
            this(type, keyLiteral, keys, null, properties);
        }

        public EntityMapping {
            Objects.requireNonNull(type, "type");
            keys = Objects.requireNonNull(keys, "keys").stream().map(Row::clean).distinct().toList();
            keyLiteral = keyLiteral == null ? null : Row.clean(keyLiteral);
            // Names a header column, so it gets the cleaning headers get, like the key columns do.
            idColumn = idColumn == null ? null : Row.clean(idColumn);
            properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
        }
    }
```

Keep the existing long comment on the compact constructor; append one sentence to it:

> An id column is the third way to identify an entity, and the only one that does not hash: the
> value is the id, so two mappings reading the same id column merge their entities deliberately,
> which is what a dump of globally-named entities needs and the footgun the option carries.

In `validate()`, inside the per-alias loop, after the key-literal checks:

```java
            refuseNul(violations, entity.idColumn(), "the id column name of entity '" + alias + "'");
            if (entity.idColumn() != null && entity.idColumn().isEmpty()) {
                violations.add("entity '" + alias + "' has a blank id column name, which no header can match");
            }
            if (entity.idColumn() != null && !entity.keys().isEmpty()) {
                violations.add("entity '" + alias + "' has both an id column and a key, and an id is read one way only");
            }
```

Note the existing key-literal check already covers `idColumn` + `keyLiteral`: a key literal with no
key is refused whatever else is set, which is why the second test above expects that wording.

- [ ] **Step 4: Build the verbatim id in `StatementBuilder`**

In `datashare-api/src/main/java/org/icij/datashare/tabular/StatementBuilder.java`, `declare` adds
the id column to the declared columns, and `entityIds` grows a branch. Replace the `keyColumns`
field and its two users with a walk over the mapping's entities in sorted order, so one alias with
`idColumn` and one with `keys` can sit side by side:

```java
    private void declare(String alias, ExtractionMapping.EntityMapping entity) {
        columns.addAll(entity.keys());
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
```

Delete the `keyColumns` field and its `import java.util.TreeMap;` if nothing else uses it (`ids` is
a `TreeMap`, so keep the import).

```java
    private Map<String, String> entityIds(Map<String, List<String>> cells, long rowNumber) {
        Map<String, String> ids = new TreeMap<>();
        for (String alias : new TreeSet<>(mapping.entities().keySet())) {
            ExtractionMapping.EntityMapping entity = mapping.entities().get(alias);
            String id = idOf(entity, cells, alias, rowNumber);
            if (id != null) {
                ids.put(alias, id);
            }
        }
        return ids;
    }

    private String idOf(ExtractionMapping.EntityMapping entity, Map<String, List<String>> cells, String alias,
                        long rowNumber) {
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
```

- [ ] **Step 5: Run the tests to verify they pass**

Run:
```
mvn -q -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app \
  -Dgpg.skip=true -Dmaven.gitcommitid.skip=true -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='StatementBuilderTest,ExtractionMappingTest,ExtractionMappingReaderTest,StatementBuilderCsvTest,StatementTest,ExtractionMappingResourceTest,ExtractionMappingServiceImplTest,CliAppTest' test
```
Expected: PASS. `ExtractionMappingReaderTest` passing is the proof that a mapping JSON without
`idColumn` still reads, and that one *with* it is no longer an unknown field.

- [ ] **Step 6: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/tabular/ExtractionMapping.java \
        datashare-api/src/main/java/org/icij/datashare/tabular/StatementBuilder.java \
        datashare-api/src/test/java/org/icij/datashare/tabular/ExtractionMappingTest.java \
        datashare-api/src/test/java/org/icij/datashare/tabular/StatementBuilderTest.java
git commit -m "feat(tabular): take an entity id verbatim from a column"
```

---

## Task 6: a declared column may be absent from a row, checked at end of run

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/tabular/StatementBuilder.java` (`requireColumns`, the `checked` field, a new public method)
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/StructuredEntityExtractionTask.java:101-110` (the stream handed to `replace`)
- Test: `datashare-api/src/test/java/org/icij/datashare/tabular/StatementBuilderTest.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/StructuredEntityExtractionTaskTest.java`

**Interfaces:**
- Consumes: `cell` returning an empty list for an absent column (Task 2).
- Produces: `public void StatementBuilder.requireEveryColumnSeen()` — throws
  `InvalidExtractionMapping` naming every column that appeared in no row, returns silently
  otherwise. Task 8's end-to-end run relies on this not firing.

- [ ] **Step 1: Write the failing tests**

Add to `datashare-api/src/test/java/org/icij/datashare/tabular/StatementBuilderTest.java`:

```java
    @Test
    public void test_a_column_absent_from_the_first_row_but_present_later_imports() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", keyed("Person", null, List.of("id"), Map.of("name", column("name"))),
                "company", keyed("Company", null, List.of("id"), Map.of("name", column("legal_name"))))), "");

        assertThat(builder.statements(row(1L, Map.of("id", "1", "name", "Jane")))).hasSize(1);
        assertThat(builder.statements(row(2L, Map.of("id", "2", "legal_name", "ACME")))).hasSize(1);
        builder.requireEveryColumnSeen();
    }

    @Test
    public void test_a_column_in_no_row_at_all_fails_at_end_of_run() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", keyed("Person", null, List.of("id"),
                                Map.of("name", column("name"), "country", column("mispelled"))))), "");

        builder.statements(row(1L, Map.of("id", "1", "name", "Jane")));

        InvalidExtractionMapping refusal =
                assertThrows(InvalidExtractionMapping.class, builder::requireEveryColumnSeen);
        assertThat(refusal.getMessage()).contains("mispelled");
    }

    @Test
    public void test_every_column_in_no_row_is_named_at_once() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", keyed("Person", null, List.of("id"),
                                Map.of("name", column("one"), "country", column("two"))))), "");

        builder.statements(row(1L, Map.of("id", "1")));

        InvalidExtractionMapping refusal =
                assertThrows(InvalidExtractionMapping.class, builder::requireEveryColumnSeen);
        assertThat(refusal.getMessage()).contains("one");
        assertThat(refusal.getMessage()).contains("two");
    }

    @Test
    public void test_a_column_missing_from_a_row_is_counted_once() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", keyed("Person", null, List.of("id"),
                                Map.of("name", column("name"), "country", column("country"))))), "");

        builder.statements(row(1L, Map.of("id", "1", "name", "Jane")));
        builder.statements(row(2L, Map.of("id", "2", "name", "John")));

        assertThat(builder.skipped().get(CELL_MISSING)).isEqualTo(1L);
    }
```

Check the existing test that pins the old row-1 behaviour (search
`StatementBuilderTest` for `the source has no column`) and rewrite it to the new contract rather
than deleting it: the same mapping over the same row must now import, and refuse at
`requireEveryColumnSeen()`.

Add to `datashare-app/src/test/java/org/icij/datashare/tasks/StructuredEntityExtractionTaskTest.java`,
following that file's existing `source(...)` / `stored(mapping(...))` helpers:

```java
    @Test
    public void test_a_mapping_naming_a_column_no_row_has_fails_and_writes_nothing() throws Exception {
        source("companies.jsonl", JsonRowSource.NDJSON_CONTENT_TYPE,
               "{\"id\":\"1\",\"name\":\"ACME\"}\n{\"id\":\"2\",\"name\":\"Globex\"}\n");
        stored(mappingWithColumn("m1", "mispelled"));

        assertThrows(InvalidExtractionMapping.class, () -> task("m1").call());

        assertThat(statements.all("prj")).isEmpty();
    }

    @Test
    public void test_a_failing_run_leaves_the_previous_run_in_place() throws Exception {
        source("companies.jsonl", JsonRowSource.NDJSON_CONTENT_TYPE, "{\"id\":\"1\",\"name\":\"ACME\"}\n");
        stored(mappingWithColumn("m1", "name"));
        task("m1").call();
        int before = statements.all("prj").size();

        stored(mappingWithColumn("m1", "mispelled"));
        assertThrows(InvalidExtractionMapping.class, () -> task("m1").call());

        assertThat(statements.all("prj")).hasSize(before);
    }
```

`mappingWithColumn(id, column)` builds the file's usual mapping with the single mapped property
reading `column`. `task(id)` is the file's existing way of constructing the task — reuse it, do not
invent a second one. `InMemoryStatementRepository` is not transactional, so the second test asserts
against whatever rollback semantics that fake has; if it has none, drop the second test from here
and add it to `JooqStatementRepositoryTest` instead, where a real transaction exists.

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```
mvn -q -pl commons-test,datashare-api -Dgpg.skip=true -Dmaven.gitcommitid.skip=true \
  -Dsurefire.failIfNoSpecifiedTests=false -Dtest=StatementBuilderTest test
```
Expected: compile failure, `cannot find symbol: method requireEveryColumnSeen()`.

- [ ] **Step 3: Move the check to end of run**

In `StatementBuilder.java`, replace the `checked` field with a `seen` set, and `requireColumns`
with:

```java
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
```

with the field declared next to `absent`:

```java
    private final Set<String> seen = new TreeSet<>();
```

Delete `private boolean checked;`.

- [ ] **Step 4: Run the check inside the write transaction**

In `datashare-app/src/main/java/org/icij/datashare/tasks/StructuredEntityExtractionTask.java`,
replace the `statements.replace(...)` call's stream argument:

```java
            // The column check rides the end of the stream rather than the end of call(): replace()
            // consumes the stream inside its transaction, so a typo rolls the retraction back with
            // everything the run wrote, which a check after replace() returned could not do.
            Stream<Statement> written = Stream.concat(
                    rows.rows().flatMap(row -> {
                        throwIfCancelled();
                        read.incrementAndGet();
                        return builder.statements(row).stream();
                    }),
                    Stream.of(builder).flatMap(checked -> {
                        checked.requireEveryColumnSeen();
                        return Stream.empty();
                    }));
            replaced = statements.replace(projectId, taskView.getId(), mapping.documentId(), builder.sheet(), written);
```

Add `import org.icij.datashare.model.Statement;` and `import java.util.stream.Stream;`.

- [ ] **Step 5: Run the tests to verify they pass**

Run:
```
mvn -q -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app \
  -Dgpg.skip=true -Dmaven.gitcommitid.skip=true -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='StatementBuilderTest,StatementBuilderCsvTest,StructuredEntityExtractionTaskTest,StructuredEntityExtractionResultTest,CliAppTest' test
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/tabular/StatementBuilder.java \
        datashare-app/src/main/java/org/icij/datashare/tasks/StructuredEntityExtractionTask.java \
        datashare-api/src/test/java/org/icij/datashare/tabular/StatementBuilderTest.java \
        datashare-app/src/test/java/org/icij/datashare/tasks/StructuredEntityExtractionTaskTest.java
git commit -m "fix(tabular): report a column no row carries at end of run, not on row 1"
```

---

## Task 7: `filters` restrict an alias to the rows it is meant for

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/tabular/ExtractionMapping.java` (`EntityMapping` record, its convenience constructors, `validate()`)
- Modify: `datashare-api/src/main/java/org/icij/datashare/tabular/StatementBuilder.java` (`declare`, `entityIds`)
- Test: `datashare-api/src/test/java/org/icij/datashare/tabular/ExtractionMappingTest.java`
- Test: `datashare-api/src/test/java/org/icij/datashare/tabular/StatementBuilderTest.java`

**Interfaces:**
- Consumes: `EntityMapping(String type, String keyLiteral, List<String> keys, String idColumn, Map<String, PropertyMapping> properties)` from Task 5; `requireEveryColumnSeen()` from Task 6.
- Produces:
  - `public record EntityMapping(String type, String keyLiteral, List<String> keys, String idColumn, Map<String, String> filters, Map<String, PropertyMapping> properties)` — final shape.
  - `Map<String, String> EntityMapping.filters()` — never null, empty when the mapping declares none.
  - The three convenience constructors, all passing `Map.of()` for `filters`:
    - `(String type, List<String> keys, Map<String, PropertyMapping> properties)`
    - `(String type, String keyLiteral, List<String> keys, Map<String, PropertyMapping> properties)`
    - `(String type, String keyLiteral, List<String> keys, String idColumn, Map<String, PropertyMapping> properties)`

- [ ] **Step 1: Write the failing tests**

Add to `datashare-api/src/test/java/org/icij/datashare/tabular/StatementBuilderTest.java`:

```java
    private static ExtractionMapping.EntityMapping filtered(String type, String idColumn, Map<String, String> filters,
                                                            Map<String, ExtractionMapping.PropertyMapping> properties) {
        return new ExtractionMapping.EntityMapping(type, null, List.of(), idColumn, filters, properties);
    }

    @Test
    public void test_an_alias_contributes_only_on_rows_its_filter_matches() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", filtered("Person", "id", Map.of("schema", "Person"), Map.of("name", column("name"))),
                "company", filtered("Company", "id", Map.of("schema", "Company"), Map.of("name", column("name"))))),
                "");

        List<Statement> statements = builder.statements(
                row(Map.of("id", "e1", "schema", "Person", "name", "Jane")));

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).entityType()).isEqualTo("Person");
    }

    @Test
    public void test_a_filtered_out_alias_is_not_counted_as_skipped() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", filtered("Person", "id", Map.of("schema", "Person"), Map.of("name", column("name"))),
                "company", filtered("Company", "id", Map.of("schema", "Company"), Map.of("name", column("name"))))),
                "");

        builder.statements(row(Map.of("id", "e1", "schema", "Person", "name", "Jane")));

        assertThat(builder.skipped().get(ENTITY_EMPTY)).isEqualTo(0L);
        assertThat(builder.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(0L);
    }

    @Test
    public void test_a_multi_valued_filter_column_matches_on_any_value() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", filtered("Person", "id", Map.of("schema", "Person"), Map.of("name", column("name"))))), "");

        List<Statement> statements = builder.statements(multi(Map.of(
                "id", List.of("e1"), "schema", List.of("LegalEntity", "Person"), "name", List.of("Jane"))));

        assertThat(statements).hasSize(1);
    }

    @Test
    public void test_a_filter_on_a_column_the_row_does_not_carry_matches_nothing() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", filtered("Person", "id", Map.of("schema", "Person"), Map.of("name", column("name"))))), "");

        assertThat(builder.statements(row(Map.of("id", "e1", "name", "Jane")))).isEmpty();
    }

    @Test
    public void test_a_filter_matches_on_the_cleaned_value() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", filtered("Person", "id", Map.of("schema", "Person"), Map.of("name", column("name"))))), "");

        assertThat(builder.statements(row(Map.of("id", "e1", "schema", " Person ", "name", "Jane"))))
                .hasSize(1);
    }

    @Test
    public void test_a_misspelled_filter_column_fails_at_end_of_run() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", filtered("Person", "id", Map.of("shcema", "Person"), Map.of("name", column("name"))))), "");

        builder.statements(row(Map.of("id", "e1", "schema", "Person", "name", "Jane")));

        InvalidExtractionMapping refusal =
                assertThrows(InvalidExtractionMapping.class, builder::requireEveryColumnSeen);
        assertThat(refusal.getMessage()).contains("shcema");
    }
```

Add to `datashare-api/src/test/java/org/icij/datashare/tabular/ExtractionMappingTest.java`:

```java
    private static ExtractionMapping.EntityMapping restricted(Map<String, String> filters) {
        return new ExtractionMapping.EntityMapping("Person", null, List.of(), "id", filters,
                Map.of("name", column("full_name")));
    }

    @Test
    public void test_a_blank_filter_column_name_is_refused() {
        assertThat(mapping("ftm", Map.of("member", restricted(Map.of(" ", "Person")))).validate().toString())
                .contains("entity 'member' has a blank filter column name, which no header can match");
    }

    @Test
    public void test_a_blank_filter_value_is_refused() {
        assertThat(mapping("ftm", Map.of("member", restricted(Map.of("schema", " ")))).validate().toString())
                .contains("entity 'member' has a blank filter value on column 'schema', which no row can match");
    }

    @Test
    public void test_a_nul_in_a_filter_column_name_is_refused() {
        assertThat(mapping("ftm", Map.of("member", restricted(Map.of("sch\u0000ema", "Person")))).validate().toString())
                .contains("the filter column name of entity 'member' holds a NUL character");
    }

    @Test
    public void test_a_nul_in_a_filter_value_is_refused() {
        assertThat(mapping("ftm", Map.of("member", restricted(Map.of("schema", "Per\u0000son")))).validate().toString())
                .contains("the filter value of entity 'member' holds a NUL character");
    }

    @Test
    public void test_a_filter_on_its_own_is_valid() {
        assertThat(mapping("ftm", Map.of("member", restricted(Map.of("schema", "Person")))).validate()).isEmpty();
    }

    @Test
    public void test_a_mapping_with_no_filters_reads_as_an_empty_map() {
        assertThat(new ExtractionMapping.EntityMapping("Person", List.of("id"), Map.of("name", column("full_name")))
                           .filters()).isEmpty();
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```
mvn -q -pl commons-test,datashare-api -Dgpg.skip=true -Dmaven.gitcommitid.skip=true \
  -Dsurefire.failIfNoSpecifiedTests=false -Dtest='StatementBuilderTest,ExtractionMappingTest' test
```
Expected: compile failure — `EntityMapping` has no six-argument constructor.

- [ ] **Step 3: Add `filters` to `EntityMapping`**

```java
    public record EntityMapping(String type, String keyLiteral, List<String> keys, String idColumn,
                                Map<String, String> filters, Map<String, PropertyMapping> properties) {
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
            keys = Objects.requireNonNull(keys, "keys").stream().map(Row::clean).distinct().toList();
            keyLiteral = keyLiteral == null ? null : Row.clean(keyLiteral);
            idColumn = idColumn == null ? null : Row.clean(idColumn);
            // A filter names a header column and holds a cell's value, so each side gets the cleaning
            // that side gets elsewhere: the comparison then runs on two values cleaned the same way,
            // which is the rule every other column comparison follows.
            filters = (filters == null ? Map.<String, String>of() : filters).entrySet().stream().collect(
                    java.util.stream.Collectors.toUnmodifiableMap(entry -> Row.clean(entry.getKey()),
                                                                  entry -> Row.clean(entry.getValue())));
            properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
        }
    }
```

Import `static java.util.stream.Collectors.toUnmodifiableMap` at the top of the file instead of the
fully-qualified call, matching the file's existing `import java.util.*` style.

A null value inside `filters` throws from `Row.clean`. Add no guard for it:
`ExtractionMappingReader.refusalOf` already turns a bare `NullPointerException` out of a record
constructor into `field 'entities.person.filters' holds a null`, which is the wording every other
null inside a field gets.

In `validate()`, inside the per-alias loop:

```java
            entity.filters().forEach((column, value) -> {
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
```

Walk `entity.filters()` through a `TreeMap` copy so the violations always come out in the same
order, the way the alias and property loops already do.

- [ ] **Step 4: Apply the filters in `StatementBuilder`**

`declare` registers the filter columns so Task 6's end-of-run check covers a misspelled one:

```java
        columns.addAll(entity.filters().keySet());
```

`idOf` gains the first line, so a filtered-out alias produces no id, no statement and no count:

```java
    private String idOf(ExtractionMapping.EntityMapping entity, Map<String, List<String>> cells, String alias,
                        long rowNumber) {
        // Not a skip: an alias whose filters do not match was never meant to apply to this row, and
        // counting it would report every row of one schema as a loss for every other schema.
        if (!entity.filters().entrySet().stream()
                   .allMatch(filter -> cells.get(filter.getKey()).contains(filter.getValue()))) {
            return null;
        }
        ...
```

`cells.get(...)` is never null: `statements(Row)` fills it for every declared column, and `cell`
returns an empty list for a column the row does not carry, which contains nothing and so matches
nothing.

- [ ] **Step 5: Run the tests to verify they pass**

Run:
```
mvn -q -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app \
  -Dgpg.skip=true -Dmaven.gitcommitid.skip=true -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='StatementBuilderTest,ExtractionMappingTest,ExtractionMappingReaderTest,StatementBuilderCsvTest,ExtractionMappingResourceTest,ExtractionMappingServiceImplTest,StructuredEntityExtractionTaskTest,CliAppTest' test
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/tabular/ExtractionMapping.java \
        datashare-api/src/main/java/org/icij/datashare/tabular/StatementBuilder.java \
        datashare-api/src/test/java/org/icij/datashare/tabular/ExtractionMappingTest.java \
        datashare-api/src/test/java/org/icij/datashare/tabular/StatementBuilderTest.java
git commit -m "feat(tabular): restrict an entity mapping to the rows a filter matches"
```

---

## Task 8: an FtM dump runs end to end

The four seams are open. This task proves they add up to the spec's goal: one mapping file, a mixed
dump, edges resolving to entities whose schemata the edge row never names.

**Files:**
- Create: `datashare-app/src/test/java/org/icij/datashare/tasks/FtmDumpExtractionTest.java`
- No fixture file: the dump is generated in the test and written to the `TemporaryFolder`, so a few thousand lines of JSONL stay out of git.

**Interfaces:**
- Consumes: everything from Tasks 2 to 7, plus the existing harness in
  `StructuredEntityExtractionTaskTest` — `ElasticsearchRule es`, `ElasticsearchIndexer indexer`,
  `InMemoryStatementRepository statements`, `ExtractionMappingServiceImpl service`, and that file's
  `source(...)` / `stored(...)` / `task(...)` helpers. Read that file first and reuse its setup
  verbatim rather than rebuilding it.
- Produces: nothing. The acceptance test.

- [ ] **Step 1: Write the failing test**

Create `datashare-app/src/test/java/org/icij/datashare/tasks/FtmDumpExtractionTest.java`. Copy the
`@ClassRule` / `@Rule` / field block and the `source`, `stored` and `task` helpers from
`StructuredEntityExtractionTaskTest`, then:

```java
    private static final int PEOPLE = 1_000;
    private static final int COMPANIES = 500;

    /** People, companies and the ownerships between them, in the shape FtmJsonExporter writes: every
     *  property an array, every line free to carry a different schema, and an edge naming its
     *  endpoints by the ids the file gave them. Every other ownership points at a person, the rest at
     *  a company, so no single schema can be guessed for the 'owner' column. */
    private static String dump() {
        StringBuilder dump = new StringBuilder();
        for (int index = 0; index < PEOPLE; index++) {
            dump.append("{\"id\":\"p%d\",\"schema\":\"Person\",\"properties\":{\"name\":[\"Person %d\"],\"firstName\":[\"First %d\"]}}\n"
                                .formatted(index, index, index));
        }
        for (int index = 0; index < COMPANIES; index++) {
            dump.append("{\"id\":\"c%d\",\"schema\":\"Company\",\"properties\":{\"name\":[\"Company %d\",\"Company %d SA\"],\"registrationNumber\":[\"R%d\"]}}\n"
                                .formatted(index, index, index, index));
        }
        for (int index = 0; index < COMPANIES; index++) {
            String owner = index % 2 == 0 ? "p" + index : "c" + index;
            dump.append("{\"id\":\"o%d\",\"schema\":\"Ownership\",\"properties\":{\"owner\":[\"%s\"],\"asset\":[\"c%d\"],\"percentage\":[]}}\n"
                                .formatted(index, owner, index));
        }
        return dump.toString();
    }

    private static final String MAPPING = """
            {"id":"dump","name":"FtM dump","model":"ftm","documentId":"%s",
             "entities":{
               "person":{"type":"Person","idColumn":"id","filters":{"schema":"Person"},
                 "properties":{"name":{"columns":["properties.name"]},
                               "firstName":{"columns":["properties.firstName"]}}},
               "company":{"type":"Company","idColumn":"id","filters":{"schema":"Company"},
                 "properties":{"name":{"columns":["properties.name"]},
                               "registrationNumber":{"columns":["properties.registrationNumber"]}}},
               "ownership":{"type":"Ownership","idColumn":"id","filters":{"schema":"Ownership"},
                 "properties":{"owner":{"columns":["properties.owner"]},
                               "asset":{"columns":["properties.asset"]},
                               "percentage":{"columns":["properties.percentage"]}}}}}
            """;

    @Test
    public void test_a_mixed_ftm_dump_builds_its_entities_and_its_edges() throws Exception {
        source("dump.jsonl", JsonRowSource.NDJSON_CONTENT_TYPE, dump());
        stored(ExtractionMappingReader.read(ExtractionMappingReader.parse(MAPPING.formatted("dump.jsonl").getBytes(UTF_8)),
                                            "prj", null));

        StructuredEntityExtractionResult result = task("dump").call();

        assertThat(result.rows()).isEqualTo(PEOPLE + COMPANIES * 2L);
        assertThat(result.indexed()).isEqualTo(PEOPLE + COMPANIES * 2L);
    }

    @Test
    public void test_every_edge_resolves_to_an_indexed_entity_whatever_its_schema() throws Exception {
        source("dump.jsonl", JsonRowSource.NDJSON_CONTENT_TYPE, dump());
        stored(ExtractionMappingReader.read(ExtractionMappingReader.parse(MAPPING.formatted("dump.jsonl").getBytes(UTF_8)),
                                            "prj", null));
        task("dump").call();

        Set<String> entityIds = statements.all("prj").stream().map(Statement::entityId).collect(toSet());
        List<Statement> endpoints = statements.all("prj").stream()
                                              .filter(statement -> statement.entityId().startsWith("o"))
                                              .filter(statement -> statement.property().equals("owner") ||
                                                                   statement.property().equals("asset")).toList();

        assertThat(endpoints).hasSize(COMPANIES * 2);
        assertThat(endpoints.stream().map(Statement::value).allMatch(entityIds::contains)).isTrue();
        assertThat(endpoints.stream().map(Statement::value).anyMatch(id -> id.startsWith("p"))).isTrue();
        assertThat(endpoints.stream().map(Statement::value).anyMatch(id -> id.startsWith("c"))).isTrue();
    }

    @Test
    public void test_a_multi_valued_property_becomes_several_statements() throws Exception {
        source("dump.jsonl", JsonRowSource.NDJSON_CONTENT_TYPE, dump());
        stored(ExtractionMappingReader.read(ExtractionMappingReader.parse(MAPPING.formatted("dump.jsonl").getBytes(UTF_8)),
                                            "prj", null));
        task("dump").call();

        List<String> names = statements.all("prj").stream()
                                       .filter(statement -> statement.entityId().equals("c0"))
                                       .filter(statement -> statement.property().equals("name"))
                                       .map(Statement::value).sorted().toList();

        assertThat(names).isEqualTo(List.of("Company 0", "Company 0 SA"));
    }
```

If `InMemoryStatementRepository` has no `all(String)`, add the smallest accessor it needs — it is a
test fake in this module, so widening it is fair game.

Check the FtM ontology actually declares `Ownership.owner` and `Ownership.asset` with the ranges
these entities satisfy before running: `FtmTargetModel` loads the real schemata, and `validate()`
will refuse the mapping otherwise. The spec's example mapping uses exactly these names, so they
should hold; if the loaded ontology disagrees, fix the property names in the test, not the
ontology.

- [ ] **Step 2: Run the test to verify it fails**

Run:
```
mvn -q -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app \
  -Dgpg.skip=true -Dmaven.gitcommitid.skip=true -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=FtmDumpExtractionTest test
```
Expected: it should already PASS if Tasks 2 to 7 are complete. If it fails, the failure names the
seam that is not finished — fix that task's code, not this test. This is the one task whose RED is
a diagnostic rather than a requirement.

- [ ] **Step 3: Fix whatever it names**

Work the failure back to the owning task's code. Do not add a special case here to make the
end-to-end pass.

- [ ] **Step 4: Run the whole tabular and extraction suite**

Run (background, poll):
```
mvn -q -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app \
  -Dgpg.skip=true -Dmaven.gitcommitid.skip=true -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='Row*,Statement*,ExtractionMapping*,*RowSource*,TabularRowReaderTest,StructuredEntityExtraction*,FtmDumpExtractionTest,CliAppTest,!IndexTaskIntTest' test
```
Expected: PASS. Note which failures, if any, are the environment gaps recorded in the project
memory (X11-dependent Tika tests, redis-dependent `@Parameterized` factories) rather than this
branch's doing, and say which is which.

- [ ] **Step 5: Run the formatter**

Run:
```
mvn -pl datashare-api,datashare-app,datashare-db -Dgpg.skip=true -Dmaven.gitcommitid.skip=true \
  intellij-code-formatter:format
```
Then re-run Step 4's test command. The formatter rewraps argument lists; running it before pushing
is what keeps CI from failing on style alone.

- [ ] **Step 6: Commit**

```bash
git add datashare-app/src/test/java/org/icij/datashare/tasks/FtmDumpExtractionTest.java
git add -u
git commit -m "test(tabular): read a mixed ftm dump end to end, edges included"
```

---

## Measured

Run 2026-10-15 against the devenv Postgres 15.17 (docker, same host as the build), schema as of
this branch, `statement` table empty at the start.

| What | Value |
|------|-------|
| Write of 6,000,000 statements through `replace` | **350,744 ms (5 min 51 s)** |
| Statements per second | **17,106** |
| `statement` total relation size | **2596 MB** (heap 1416 MB, indexes ~1180 MB) |
| Rows committed | 6,000,000 |
| Index rebuild | **not measured**, see below |

Decision: **no performance work.** The write landed in under six minutes, two orders of magnitude
inside the task's `P1D` activity timeout. None of the three levers
(`reWriteBatchedInserts`, commit-per-chunk, `COPY` into a temp table) gets built. Tasks 2 to 8
proceed unchanged.

Caveats a later reader should know:

- This is a cold insert: every row took the `ON CONFLICT ... DO UPDATE` branch's insert path, none
  of them the update path. A re-run over unchanged data exercises the conditional update instead,
  which the `WHERE` clause at `JooqStatementRepository.java:143-145` is built to make cheap, but that
  was not timed.
- One transaction stayed open for the whole 5 min 51 s, which is the vacuum-blocking window the
  spec names. At this duration it is not worth acting on.
- The generator wrote six properties per entity over 1,000,000 entities, matching the spec's
  "several million statements" for a million-line dump.
- **The index rebuild was not measured.** `EntitiesIndexRebuilder` needs an `Indexer` and a
  `StatementRepository` together, which only `datashare-app` has, and neither HikariCP nor the
  Postgres driver is on that module's test classpath: `datashare-db` is `provided` there
  (`datashare-app/pom.xml:155-157`), so its runtime transitives are dropped. Measuring it means
  adding test-scoped dependencies to a production pom for a benchmark, which was not worth it once
  the write number already settled the decision. If it is wanted later, that is the change to make.
  Its shape is known from the code: one `_delete_by_query` plus a refill of every entity in the
  project in chunks of 1,000 (`EntitiesIndexRebuilder.CHUNK_SIZE`), and it runs in full on every
  mapping run whatever this branch does.
- The benchmark rows were truncated out of `dstest` afterwards; the table is back to 32 kB.
