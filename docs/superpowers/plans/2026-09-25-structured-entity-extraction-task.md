# StructuredEntityExtractionTask Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One background task that runs a persisted extraction mapping from a tabular document to statements to a refreshed entities index, plus the Guice and factory wiring that lets Datashare build it.

**Architecture:** A single `StructuredEntityExtractionTask` in `datashare-app` reads the mapping, guards the read, streams the document's rows through `StatementBuilder`, writes via `StatementRepository.replace`, then rebuilds the project's entities index in full. Two supporting shape changes make that possible: `RowSource.rows` reports the sheet it resolved, and `ExtractionMapping` can name an embedded document's root.

**Tech Stack:** Java 21, Guice (assisted injection), jOOQ, Elasticsearch, JUnit 4, fest-assert, Mockito.

**Spec:** `docs/superpowers/specs/2026-09-25-structured-entity-extraction-task-design.md`

## Global Constraints

- Branch: `feat/2206-structured-entity-extraction-task`, already created off `main`.
- `docs/superpowers` is in `.gitignore`. Never `git add -f` the spec or this plan.
- No `Stage` enum entry, no `StageRunCommand` help line, no `CliApp` stage-to-task mapping, no REST route. Those belong to #2207.
- The task factory method name must be exactly `createStructuredEntityExtractionTask`. `TaskFactoryHelper.java:16` resolves `create%s` from the class's simple name by reflection with `(Task.class, Function.class)`; a mismatch fails at runtime, not at compile time.
- Tests are JUnit 4 (`org.junit.Test`) with `org.fest.assertions.Assertions.assertThat`. Match the surrounding files.
- Comments: default to zero. Write one only for a non-obvious *why*. Never restate what a line does. Do not add comments to lines you did not change.
- No em dashes in any prose you write.
- Commit messages are semantic (`feat(tabular): ...`), no Claude attribution, no co-author trailers.
- `datashare-db`'s `DbSetupRule` is **not** on `datashare-app`'s test classpath (`datashare-app/pom.xml:148` declares `datashare-db` at `provided` scope with no `test-jar`). Task tests use in-memory fakes plus `ElasticsearchRule`, never a real database.

## Review Focus

Five things the spec implies that no task's own happy-path tests would exercise. Each gets a test in the task that owns the code, listed in that task's steps.

1. **A project id that fails `Project.NAME_PATTERN` is only rejected by `rebuild`, after the statements are already written.** `EntitiesIndexRebuilder.rebuild` validates the name because an unvalidated id reaches a `_delete_by_query` URL path, but by then `replace` has committed. The task must validate the project id before it writes anything. Test in Task 4.
2. **The task id must fit `run_id varchar(96)`.** `Task`'s id is `name + "-" + randomUUID()`, which for this class is 92 characters. Four characters of headroom; a longer class name overflows the column and fails at insert time. Test in Task 4.
3. **A header-only source (zero data rows) is a clean success, not a failure and not a warning.** It retracts nothing and writes nothing, so `retracted > written` stays false. Test in Task 4.
4. **A content type no reader supports must surface the reader's own message.** `TabularRowReader.select` throws `IllegalArgumentException` naming the supported types; the task must let it through rather than turning it into a null or a routing failure. Test in Task 4.
5. **A workbook sheet name carrying a NUL must fail before any statement is written.** The sheet now comes from the reader rather than the mapping, so `ExtractionMapping.validate()` never saw it; `StatementBuilder`'s constructor is the only guard, and it runs before the write. Test in Task 1.

---

### Task 1: `RowSource` reports the sheet it resolved

**Files:**
- Create: `datashare-api/src/main/java/org/icij/datashare/tabular/Rows.java`
- Modify: `datashare-api/src/main/java/org/icij/datashare/tabular/RowSource.java`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tabular/DelimitedRowSource.java:44-52`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tabular/JsonRowSource.java:52,123-125`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tabular/WorkbookRowSource.java:45-62`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tabular/TikaTableRowSource.java:66-73`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tabular/TabularRowReader.java:33,68-85`
- Test: `datashare-app/src/test/java/org/icij/datashare/tabular/WorkbookRowSourceTest.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tabular/TikaTableRowSourceTest.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tabular/DelimitedRowSourceTest.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tabular/JsonRowSourceTest.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tabular/RowSourceEquivalenceTest.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tabular/TabularRowReaderTest.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tabular/StatementBuilderCsvTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `org.icij.datashare.tabular.Rows`, a record with components `String sheet` and `Stream<Row> rows`. `RowSource.rows(InputStream, RowSourceOptions)` returns `Rows`. `TabularRowReader.rows(Project, String documentId, String rootId, RowSourceOptions)` returns `Rows`. `TabularRowReader.CONTENT_FIELDS` becomes `public static final List<String>`.

- [ ] **Step 1: Write the failing test for the workbook reader**

Add to `datashare-app/src/test/java/org/icij/datashare/tabular/WorkbookRowSourceTest.java`. Read the file first and reuse whatever helper it already has for building a workbook; the snippet below writes its own so it is self-contained.

```java
@Test
public void test_reports_the_sheet_name_when_the_options_asked_for_an_index() throws Exception {
    byte[] content = workbook("Employees", List.of("id", "name"), List.of(List.of("1", "ACME")));

    try (InputStream stream = new ByteArrayInputStream(content);
         Rows rows = source.rows(stream, RowSourceOptions.defaults().withSheet("1"))) {
        assertThat(rows.sheet()).isEqualTo("Employees");
    }
}
```

`RowSourceOptions` has no `withSheet`, so add one next to `withDelimiter` in
`datashare-api/src/main/java/org/icij/datashare/tabular/RowSourceOptions.java`:

```java
public RowSourceOptions withSheet(String newSheet) {
    return new RowSourceOptions(contentType, charset, delimiter, quote, newSheet, table);
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
mvn -q -pl datashare-app -am test -Dtest=WorkbookRowSourceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL to compile, `cannot find symbol: class Rows`.

- [ ] **Step 3: Create the `Rows` record**

`datashare-api/src/main/java/org/icij/datashare/tabular/Rows.java`:

```java
package org.icij.datashare.tabular;

import java.util.stream.Stream;

/**
 * The rows of one section of a source, with the section the reader resolved: a workbook's sheet
 * name, the Tika fallback's table index, or null for a format with one table. The caller gets what
 * was read rather than what was asked for, because a statement id hashes the section and retraction
 * keys on it, so "1" and "Sheet1" have to agree while two tables of one document must not.
 */
public record Rows(String sheet, Stream<Row> rows) implements AutoCloseable {
    @Override
    public void close() {
        rows.close();
    }
}
```

- [ ] **Step 4: Change the `RowSource` interface**

In `datashare-api/src/main/java/org/icij/datashare/tabular/RowSource.java`, change the method and drop the now-unused `Stream` import:

```java
    Rows rows(InputStream source, RowSourceOptions options) throws IOException;
```

Update the interface javadoc's closing sentence to say the returned `Rows` is closed by the caller rather than "the returned stream".

- [ ] **Step 5: Change the four readers**

`DelimitedRowSource.java`, replacing the return at line 52:

```java
    @Override
    public Rows rows(InputStream source, RowSourceOptions options) throws IOException {
        CSVParser parser = parse(source, options);
        try {
            Iterator<CSVRecord> records = parser.iterator();
            if (!records.hasNext()) {
                throw new IllegalArgumentException("no header row: the source is empty");
            }
            List<String> headers = Row.headers(records.next().toList());
            return new Rows(null, stream(records, headers).onClose(() -> close(parser)));
        } catch (RuntimeException failure) {
            closeQuietly(parser);
            throw failure;
        }
    }
```

`JsonRowSource.java`, changing the signature at line 52 and wrapping the return at lines 123-125:

```java
        return new Rows(null, StreamSupport.stream(
                                    Spliterators.spliteratorUnknownSize(rows, Spliterator.ORDERED | Spliterator.NONNULL), false)
                            .onClose(() -> close(parser)));
```

`WorkbookRowSource.java`, replacing the try block at lines 55-62:

```java
        try {
            Sheet sheet = selectSheet(workbook, options.sheet());
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            return new Rows(sheet.getSheetName(), read(sheet, evaluator).stream().onClose(() -> close(workbook)));
        } catch (RuntimeException failure) {
            close(workbook);
            throw failure;
        }
```

`TikaTableRowSource.java`, replacing lines 66-73. The resolved index is the same expression `selectTable` uses, so it is computed here rather than threaded back out of `read`; an out-of-range index still fails inside `read`:

```java
    @Override
    public Rows rows(InputStream source, RowSourceOptions options) throws IOException {
        // Nothing here is lazy: the extractor has consumed the source to exhaustion before the first
        // row is built, so the source is released on the way out rather than by the returned stream.
        try {
            String table = String.valueOf(options.table() == null ? 1 : options.table());
            return new Rows(table, read(source, options).stream());
        } finally {
            close(source);
        }
    }
```

- [ ] **Step 6: Change `TabularRowReader`**

In `datashare-app/src/main/java/org/icij/datashare/tabular/TabularRowReader.java`, make `CONTENT_FIELDS` public (line 33) so the task can exclude the same fields, and return `Rows` from `rows` (lines 68-85):

```java
    public static final List<String> CONTENT_FIELDS = List.of("content", "content_translated");
```

```java
    public Rows rows(Project project, String documentId, String rootId, RowSourceOptions options) throws
            IOException {
        // Excluding the extracted text, as DocumentSourceAccess does: only four metadata fields are
        // read here, and a large tabular document's content would be a second full copy in heap.
        Document document =
                indexer.get(project.getName(), documentId, rootId == null ? documentId : rootId, CONTENT_FIELDS);
        if (document == null) {
            throw new IllegalArgumentException("no such document in " + project.getName() + ": " + documentId);
        }
        RowSourceOptions resolved = resolve(document, options);
        RowSource reader = select(resolved.contentType());
        InputStream source = sourceExtractor.getSource(project, document);
        try {
            Rows rows = reader.rows(source, resolved);
            return new Rows(rows.sheet(), rows.rows().onClose(() -> closeQuietly(source)));
        } catch (IOException | RuntimeException failure) {
            closeQuietly(source);
            throw failure;
        }
    }
```

Also remove the paragraph in the class javadoc claiming that wiring to `DocumentSourceAccess` "belongs to #2206", and replace it with what is now true: the project check and the root size check are applied by `StructuredEntityExtractionTask` and by the #2207 endpoints, not here.

- [ ] **Step 7: Update every test call site**

Seven test files call `.rows(...)`. In each, change `Stream<Row> rows = ...rows(...)` to `Rows rows = ...rows(...)` and read `rows.rows().toList()` where the stream was consumed. `Rows` implements `AutoCloseable`, so the existing try-with-resources blocks keep working unchanged. Add `import org.icij.datashare.tabular.Rows;` where the test is outside the `tabular` package.

- [ ] **Step 8: Run the tabular tests to verify they pass**

```bash
mvn -q -pl datashare-app -am test -Dtest=WorkbookRowSourceTest,TikaTableRowSourceTest,DelimitedRowSourceTest,JsonRowSourceTest,RowSourceEquivalenceTest,TabularRowReaderTest,StatementBuilderCsvTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. Note the commas: `-Dtest=A+B` silently runs nothing and reports green.

- [ ] **Step 9: Add the remaining sheet assertions**

In `TikaTableRowSourceTest`, on a source with two tables:

```java
@Test
public void test_reports_the_table_index_it_selected() throws Exception {
    try (Rows rows = source.rows(stream(twoTables()), RowSourceOptions.defaults())) {
        assertThat(rows.sheet()).isEqualTo("1");
    }
}
```

In `DelimitedRowSourceTest` and `JsonRowSourceTest`, one each:

```java
@Test
public void test_reports_no_sheet_because_the_source_has_one_table() throws Exception {
    try (Rows rows = source.rows(new ByteArrayInputStream("id,name\n1,ACME\n".getBytes(StandardCharsets.UTF_8)),
                                 RowSourceOptions.defaults())) {
        assertThat(rows.sheet()).isNull();
    }
}
```

- [ ] **Step 10: Add the Review Focus test for a NUL in a sheet name**

In `WorkbookRowSourceTest`, proving the guard that now matters because the sheet reaches `StatementBuilder` from the reader rather than from the validated mapping. POI refuses a NUL in `createSheet`, so build the name through the reader's own path and assert `StatementBuilder` is what refuses it:

```java
@Test
public void test_a_sheet_name_holding_a_nul_is_refused_before_any_statement() {
    ExtractionMapping mapping = new ExtractionMapping("m1", "prj", null, "companies", "ftm", "docId",
            RowSourceOptions.defaults(),
            Map.of("c", new ExtractionMapping.EntityMapping("Company", List.of("id"),
                    Map.of("name", new ExtractionMapping.PropertyMapping(List.of("name"), null, null, null, null)))));

    assertThrows(IllegalArgumentException.class, () -> new StatementBuilder(mapping, "Sheet 1"));
}
```

If `WorkbookRowSourceTest` has no `ExtractionMapping` import, put this test in
`datashare-api/src/test/java/org/icij/datashare/tabular/StatementBuilderTest.java` instead, where those imports already exist.

- [ ] **Step 11: Run the tabular tests again**

```bash
mvn -q -pl datashare-app -am test -Dtest=WorkbookRowSourceTest,TikaTableRowSourceTest,DelimitedRowSourceTest,JsonRowSourceTest,RowSourceEquivalenceTest,TabularRowReaderTest,StatementBuilderCsvTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -q -pl datashare-api -am test -Dtest=StatementBuilderTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/tabular datashare-api/src/test/java/org/icij/datashare/tabular datashare-app/src/main/java/org/icij/datashare/tabular datashare-app/src/test/java/org/icij/datashare/tabular
git commit -m "feat(tabular): let a reader report the sheet it resolved"
```

---

### Task 2: a mapping can name an embedded document's root

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/tabular/ExtractionMapping.java:16-29`
- Test: `datashare-api/src/test/java/org/icij/datashare/tabular/ExtractionMappingTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `ExtractionMapping` gains a `String rootId` component between `documentId` and `options`, plus a secondary constructor without it that passes `null`, so the 13 existing construction sites keep compiling.

- [ ] **Step 1: Write the failing test**

Add to `datashare-api/src/test/java/org/icij/datashare/tabular/ExtractionMappingTest.java`:

```java
@Test
public void test_a_mapping_without_a_root_id_names_a_root_document() {
    assertThat(mapping().rootId()).isNull();
}

@Test
public void test_a_mapping_round_trips_its_root_id_through_json() throws Exception {
    ExtractionMapping mapping = new ExtractionMapping("m1", "prj", null, "companies", "ftm", "docId", "zipId",
            RowSourceOptions.defaults(),
            Map.of("c", new ExtractionMapping.EntityMapping("Company", List.of("id"),
                    Map.of("name", new ExtractionMapping.PropertyMapping(List.of("name"), null, null, null, null)))));

    String json = JsonObjectMapper.getMapper().writeValueAsString(mapping);

    assertThat(JsonObjectMapper.readValue(json, ExtractionMapping.class).rootId()).isEqualTo("zipId");
}
```

`mapping()` is whatever helper the file already uses to build a valid mapping; reuse it rather than writing a second one. Add `import org.icij.datashare.json.JsonObjectMapper;` if it is missing. `MAPPER` itself is private; `getMapper()` and the static `readValue(String, Class)` are the public seams, and `readValue` is what `JooqExtractionMappingRepository.java:97` uses to load a stored definition.

- [ ] **Step 2: Run it to verify it fails**

```bash
mvn -q -pl datashare-api -am test -Dtest=ExtractionMappingTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL to compile, `cannot find symbol: method rootId()`.

- [ ] **Step 3: Add the component and the compatibility constructor**

In `datashare-api/src/main/java/org/icij/datashare/tabular/ExtractionMapping.java`, change the record header and add the secondary constructor, mirroring what `EntityMapping` already does for `keyLiteral`:

```java
public record ExtractionMapping(String id, String projectId, String userId, String name, String model, String documentId, String rootId, RowSourceOptions options, Map<String, EntityMapping> entities) {
    /** The container an embedded document was extracted from, or null for a root document:
     *  elasticsearch routes an embedded document by its root, so a mapping over a CSV inside a ZIP
     *  cannot be read without it. */
    public ExtractionMapping(String id, String projectId, String userId, String name, String model, String documentId,
                             RowSourceOptions options, Map<String, EntityMapping> entities) {
        this(id, projectId, userId, name, model, documentId, null, options, entities);
    }
```

Leave the compact constructor's body as it is: `rootId` needs no validation beyond what `validate()` already does for NUL characters, which the next step adds.

- [ ] **Step 4: Refuse a NUL in the root id**

In `validate()`, next to the existing `refuseNul` calls:

```java
        refuseNul(violations, rootId, "the root id");
```

Check `refuseNul`'s handling of a null value before adding this; if it does not already skip null, guard the call rather than changing `refuseNul`, since `options.sheet()` is nullable too and already passes through it.

- [ ] **Step 5: Run the test to verify it passes**

```bash
mvn -q -pl datashare-api -am test -Dtest=ExtractionMappingTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 6: Run everything that constructs a mapping**

```bash
mvn -q -pl datashare-api -am test -Dtest=ExtractionMappingTest,StatementBuilderTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -q -pl datashare-db -am test -Dtest=JooqExtractionMappingRepositoryTest,JooqStatementRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. The `JooqExtractionMappingRepositoryTest` run is the one that proves Jackson still picks the canonical constructor now that the record has two.

- [ ] **Step 7: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/tabular/ExtractionMapping.java datashare-api/src/test/java/org/icij/datashare/tabular/ExtractionMappingTest.java
git commit -m "feat(tabular): let a mapping name an embedded document's root"
```

---

### Task 3: the task result type and its registrations

**Files:**
- Create: `datashare-app/src/main/java/org/icij/datashare/tasks/StructuredEntityExtractionResult.java`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/TaskResultSubtypes.java:9-10`
- Modify: `datashare-api/src/main/java/org/icij/datashare/tasks/TaskType.java:16-18`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/StructuredEntityExtractionResultTest.java`

**Interfaces:**
- Consumes: `StatementBuilder.Skip` from `datashare-api`.
- Produces: `StructuredEntityExtractionResult(long rows, int retracted, int written, int indexed, Map<StatementBuilder.Skip, Long> skipped)`, serializable and registered. Task 4 returns it; Task 5 names it in the factory signature.

- [ ] **Step 1: Write the failing test**

`datashare-app/src/test/java/org/icij/datashare/tasks/StructuredEntityExtractionResultTest.java`:

```java
package org.icij.datashare.tasks;

import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.tabular.StatementBuilder;
import org.junit.Test;

import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class StructuredEntityExtractionResultTest {
    @Test
    public void test_round_trips_through_json_with_its_skip_counts() throws Exception {
        StructuredEntityExtractionResult result = new StructuredEntityExtractionResult(
                3L, 0, 5, 2, Map.of(StatementBuilder.Skip.ENTITY_UNIDENTIFIED, 1L));

        String json = JsonObjectMapper.getMapper().writeValueAsString(result);

        assertThat(JsonObjectMapper.readValue(json, StructuredEntityExtractionResult.class)).isEqualTo(result);
    }

    @Test
    public void test_is_a_registered_task_result_subtype() {
        assertThat(java.util.Arrays.stream(TaskResultSubtypes.values())
                                   .anyMatch(subtype -> subtype.getType() == StructuredEntityExtractionResult.class))
                .isTrue();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
mvn -q -pl datashare-app -am test -Dtest=StructuredEntityExtractionResultTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL to compile, `cannot find symbol: class StructuredEntityExtractionResult`.

- [ ] **Step 3: Write the record**

`datashare-app/src/main/java/org/icij/datashare/tasks/StructuredEntityExtractionResult.java`:

```java
package org.icij.datashare.tasks;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import org.icij.datashare.tabular.StatementBuilder;

import java.io.Serializable;
import java.util.Map;

/** What one run of a mapping did. The skip counts travel with the rest because a run reporting rows
 *  read and statements written, and nothing else, presents a total loss as a clean import: a blank
 *  key column alone accounts for every row of a file. */
@JsonTypeName("StructuredEntityExtractionResult")
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@type")
public record StructuredEntityExtractionResult(long rows, int retracted, int written, int indexed,
                                               Map<StatementBuilder.Skip, Long> skipped) implements Serializable {
    public StructuredEntityExtractionResult(@JsonProperty("rows") long rows, @JsonProperty("retracted") int retracted,
                                            @JsonProperty("written") int written, @JsonProperty("indexed") int indexed,
                                            @JsonProperty("skipped") Map<StatementBuilder.Skip, Long> skipped) {
        this.rows = rows;
        this.retracted = retracted;
        this.written = written;
        this.indexed = indexed;
        this.skipped = Map.copyOf(skipped);
    }
}
```

- [ ] **Step 4: Register it in both enums**

`TaskResultSubtypes.java`, extending the constant list:

```java
    LONG(Long.class), URI_RESULT(UriResult.class), BATCH_SEARCH_RUNNER_RESULT(BatchSearchRunnerResult.class),
    BATCH_DOWNLOAD_RUNNER_RESULT(BatchDownloadRunnerResult.class),
    STRUCTURED_ENTITY_EXTRACTION_RESULT(StructuredEntityExtractionResult.class);
```

`TaskType.java`, after `GRANT_ADMIN_POLICY`:

```java
    GRANT_ADMIN_POLICY("org.icij.datashare.tasks.GrantAdminPolicyTask"),
    STRUCTURED_ENTITY_EXTRACTION("org.icij.datashare.tasks.StructuredEntityExtractionTask");
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
mvn -q -pl datashare-app -am test -Dtest=StructuredEntityExtractionResultTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tasks/StructuredEntityExtractionResult.java datashare-app/src/main/java/org/icij/datashare/tasks/TaskResultSubtypes.java datashare-api/src/main/java/org/icij/datashare/tasks/TaskType.java datashare-app/src/test/java/org/icij/datashare/tasks/StructuredEntityExtractionResultTest.java
git commit -m "feat(tasks): add the structured entity extraction result"
```

---

### Task 4: `StructuredEntityExtractionTask`

**Files:**
- Create: `datashare-app/src/main/java/org/icij/datashare/tasks/StructuredEntityExtractionTask.java`
- Create: `datashare-app/src/test/java/org/icij/datashare/tasks/InMemoryStatementRepository.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/StructuredEntityExtractionTaskTest.java`

**Interfaces:**
- Consumes: `Rows` and `TabularRowReader.CONTENT_FIELDS` from Task 1, `ExtractionMapping.rootId()` from Task 2, `StructuredEntityExtractionResult` from Task 3.
- Produces: `StructuredEntityExtractionTask`, constructed as
  `new StructuredEntityExtractionTask(Indexer, StatementRepository, ExtractionMappingRepository, PropertiesProvider, Task<StructuredEntityExtractionResult>, Function<Double, Void>)`.
  Task 5 declares exactly this pair of assisted parameters on the factory.

- [ ] **Step 1: Write the in-memory statement repository the tests need**

`DbSetupRule` is not on this module's test classpath, so the fake carries the `replace` semantics the tests assert against.

`datashare-app/src/test/java/org/icij/datashare/tasks/InMemoryStatementRepository.java`:

```java
package org.icij.datashare.tasks;

import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.Statement;
import org.icij.datashare.model.StatementRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

public class InMemoryStatementRepository implements StatementRepository {
    final Map<String, Statement> stored = new LinkedHashMap<>();

    @Override
    public int save(String projectId, String runId, Stream<Statement> statements) {
        List<Statement> written = statements.filter(statement -> !stored.containsKey(statement.id())).toList();
        written.forEach(statement -> stored.put(statement.id(), statement));
        return written.size();
    }

    @Override
    public int deleteBySheet(String projectId, String documentId, String sheet) {
        String section = Statement.Provenance.sheetOrEmpty(sheet);
        List<String> doomed = stored.values().stream()
                                    .filter(statement -> statement.provenance().documentId().equals(documentId))
                                    .filter(statement -> statement.provenance().sheet().equals(section))
                                    .map(Statement::id).collect(toList());
        doomed.forEach(stored::remove);
        return doomed.size();
    }

    @Override
    public Replaced replace(String projectId, String runId, String documentId, String sheet,
                            Stream<Statement> statements) {
        int retracted = deleteBySheet(projectId, documentId, sheet);
        int written = 0;
        for (Statement statement : statements.toList()) {
            stored.put(statement.id(), statement);
            written++;
        }
        return new Replaced(retracted, written);
    }

    @Override
    public <R> R entities(String projectId, Function<Stream<ModelEntity>, R> consumer) {
        List<ModelEntity> entities = new ArrayList<>();
        stored.values().stream().collect(groupingBy(Statement::entityId))
              .forEach((id, group) -> entities.add(ModelEntity.from(group, Set.of("1"))));
        return consumer.apply(entities.stream());
    }

    @Override
    public Optional<ModelEntity> entity(String projectId, String entityId) {
        return entities(projectId, all -> all.filter(entity -> entity.id().equals(entityId)).findFirst());
    }
}
```

The retraction has to run before the stream is consumed, matching `JooqStatementRepository`, which puts the delete in the first chunk's transaction.

- [ ] **Step 2: Write the failing test**

`datashare-app/src/test/java/org/icij/datashare/tasks/StructuredEntityExtractionTaskTest.java`:

```java
package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.tabular.ExtractionMapping;
import org.icij.datashare.tabular.ExtractionMappingRepository;
import org.icij.datashare.tabular.RowSourceOptions;
import org.icij.datashare.tabular.StatementBuilder;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.tabular.TabularRowReader.CONTENT_FIELDS;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class StructuredEntityExtractionTaskTest {
    @ClassRule public static ElasticsearchRule es = new ElasticsearchRule();
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private final ElasticsearchIndexer indexer =
            spy(new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True));
    private final InMemoryStatementRepository statements = new InMemoryStatementRepository();
    private final ExtractionMappingRepository mappings = mock(ExtractionMappingRepository.class);

    @Before
    public void setUp() throws IOException {
        es.delete(Project.entitiesIndex("prj"));
    }

    @After
    public void tearDown() throws IOException {
        es.delete(Project.entitiesIndex("prj"));
    }

    @Test
    public void test_runs_a_mapping_into_statements_and_the_entities_index() throws Exception {
        source("companies.csv", "text/csv", "id,name\n1,ACME\n2,Globex\n");
        stored(mapping("m1"));

        StructuredEntityExtractionResult result = task("m1").call();

        assertThat(result.rows()).isEqualTo(2L);
        assertThat(result.written()).isEqualTo(2);
        assertThat(result.retracted()).isEqualTo(0);
        assertThat(result.indexed()).isEqualTo(2);
        assertThat(result.skipped().get(StatementBuilder.Skip.ENTITY_UNIDENTIFIED)).isEqualTo(0L);
        assertThat(indexer.exists(Project.entitiesIndex("prj"))).isTrue();
    }

    @Test
    public void test_running_it_again_leaves_the_same_statements() throws Exception {
        source("companies.csv", "text/csv", "id,name\n1,ACME\n2,Globex\n");
        stored(mapping("m1"));
        task("m1").call();
        List<String> first = List.copyOf(statements.stored.keySet());

        StructuredEntityExtractionResult second = task("m1").call();

        assertThat(List.copyOf(statements.stored.keySet())).isEqualTo(first);
        assertThat(second.retracted()).isEqualTo(second.written());
    }

    private void source(String filename, String contentType, String content) throws Exception {
        Path file = folder.getRoot().toPath().resolve(filename);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Document document = DocumentBuilder.createDoc("docId").with(file).ofContentType(contentType)
                                           .with(StandardCharsets.UTF_8).with(Map.of()).build();
        doReturn(document).when(indexer).get("prj", "docId", "docId", CONTENT_FIELDS);
    }

    private ExtractionMapping mapping(String id) {
        return new ExtractionMapping(id, "prj", null, "companies", "ftm", "docId", RowSourceOptions.defaults(),
                Map.of("c", new ExtractionMapping.EntityMapping("Company", List.of("id"),
                        Map.of("name", new ExtractionMapping.PropertyMapping(List.of("name"), null, null, null, null)))));
    }

    private void stored(ExtractionMapping mapping) {
        when(mappings.get("prj", mapping.id())).thenReturn(Optional.of(mapping));
    }

    private StructuredEntityExtractionTask task(String mappingId) {
        Task<StructuredEntityExtractionResult> taskView = new Task<>(
                StructuredEntityExtractionTask.class.getName(), User.localUser("jane", List.of("prj")),
                Map.of("defaultProject", "prj", "mappingId", mappingId));
        return new StructuredEntityExtractionTask(indexer, statements, mappings, new PropertiesProvider(), taskView,
                                                  null);
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

```bash
mvn -q -pl datashare-app -am test -Dtest=StructuredEntityExtractionTaskTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL to compile, `cannot find symbol: class StructuredEntityExtractionTask`.

- [ ] **Step 4: Write the task**

`datashare-app/src/main/java/org/icij/datashare/tasks/StructuredEntityExtractionTask.java`:

```java
package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.CancellableTask;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.model.EntitiesIndexRebuilder;
import org.icij.datashare.model.Statement;
import org.icij.datashare.model.StatementRepository;
import org.icij.datashare.tabular.ExtractionMapping;
import org.icij.datashare.tabular.ExtractionMappingRepository;
import org.icij.datashare.tabular.Rows;
import org.icij.datashare.tabular.StatementBuilder;
import org.icij.datashare.tabular.TabularRowReader;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.datashare.utils.DocumentVerifier;
import org.icij.task.DefaultTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;

@TemporalSingleActivityWorkflow(name = "structured-entity-extraction",
                                activityOptions = @ActivityOpts(timeout = "P1D"))
@TaskGroup(TaskGroupType.Java)
public class StructuredEntityExtractionTask extends DefaultTask<StructuredEntityExtractionResult>
        implements UserTask, CancellableTask {
    public static final String MAPPING_ID_OPT = "mappingId";
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final StatementRepository statements;
    private final ExtractionMappingRepository mappings;
    private final TabularRowReader reader;
    private final DocumentVerifier verifier;
    private final Task<StructuredEntityExtractionResult> taskView;
    private final Function<Double, Void> updateCallback;
    private final String projectId;
    private final String mappingId;
    private volatile Thread taskThread;

    @Inject
    public StructuredEntityExtractionTask(Indexer indexer, StatementRepository statements,
                                          ExtractionMappingRepository mappings, PropertiesProvider propertiesProvider,
                                          @Assisted Task<StructuredEntityExtractionResult> taskView,
                                          @Assisted Function<Double, Void> updateCallback) {
        this.indexer = indexer;
        this.statements = statements;
        this.mappings = mappings;
        this.reader = new TabularRowReader(indexer, new SourceExtractor(propertiesProvider));
        this.verifier = new DocumentVerifier(indexer, propertiesProvider);
        this.taskView = taskView;
        this.updateCallback = updateCallback;
        PropertiesProvider args = new PropertiesProvider(taskView.args);
        this.projectId = args.get(DEFAULT_PROJECT_OPT).filter(id -> !id.isBlank()).orElse(null);
        this.mappingId = args.get(MAPPING_ID_OPT).filter(id -> !id.isBlank()).orElse(null);
    }

    @Override
    public StructuredEntityExtractionResult call() throws Exception {
        taskThread = Thread.currentThread();
        require(projectId != null, "no '" + DEFAULT_PROJECT_OPT + "' in the task arguments");
        require(mappingId != null, "no '" + MAPPING_ID_OPT + "' in the task arguments");
        // Ahead of the write rather than at rebuild time: EntitiesIndexRebuilder refuses a bad name
        // because it reaches a _delete_by_query path, but by then the statements are committed.
        require(Project.NAME_PATTERN.matcher(projectId).matches(), "bad format for project id: '" + projectId + "'");
        User user = taskView.getUser();
        // A CLI run carries nullUser(), which is granted nothing; the HTTP triggers carry a real one.
        require(user.isNull() || user.isGranted(projectId), "user '" + user.id + "' is not granted " + projectId);
        ExtractionMapping mapping = mappings.get(projectId, mappingId)
                                            .orElseThrow(() -> new IllegalArgumentException(
                                                    "no mapping '" + mappingId + "' in " + projectId));
        Project project = Project.project(projectId);
        Document document = indexer.get(projectId, mapping.documentId(),
                                        mapping.rootId() == null ? mapping.documentId() : mapping.rootId(),
                                        TabularRowReader.CONTENT_FIELDS);
        require(document != null, "no such document in " + projectId + ": " + mapping.documentId());
        require(verifier.isRootDocumentSizeAllowed(document, project),
                "the file or its parent is too large: " + mapping.documentId());

        AtomicLong read = new AtomicLong();
        StatementRepository.Replaced replaced;
        StatementBuilder builder;
        try (Rows rows = reader.rows(project, mapping.documentId(), mapping.rootId(), mapping.options())) {
            builder = new StatementBuilder(mapping, rows.sheet());
            replaced = statements.replace(projectId, taskView.getId(), mapping.documentId(), rows.sheet(),
                                          rows.rows().flatMap(row -> {
                                              // Throwing rather than truncating: a takeWhile would
                                              // commit a partial rewrite and rebuild the index on it,
                                              // reporting a cut-short import as a clean one.
                                              if (Thread.currentThread().isInterrupted()) {
                                                  throw new IllegalStateException("extraction was cancelled");
                                              }
                                              read.incrementAndGet();
                                              return builder.statements(row).stream();
                                          }));
        }
        progress(0.5);
        if (replaced.retracted() > replaced.written()) {
            logger.warn("mapping '{}' retracted {} statements and wrote {} for document {}: statements stored for "
                        + "this document and sheet were removed and not put back, which is what happens when another "
                        + "mapping targets the same sheet", mappingId, replaced.retracted(), replaced.written(),
                        mapping.documentId());
        }
        int indexed = new EntitiesIndexRebuilder(indexer, statements).rebuild(projectId);
        progress(1.0);
        StructuredEntityExtractionResult result = new StructuredEntityExtractionResult(
                read.get(), replaced.retracted(), replaced.written(), indexed, builder.skipped());
        logger.info("mapping '{}' read {} rows, retracted {}, wrote {}, indexed {} entities, skipped {}", mappingId,
                    result.rows(), result.retracted(), result.written(), result.indexed(), result.skipped());
        return result;
    }

    @Override
    public void cancel(boolean requeue) {
        ofNullable(taskThread).ifPresent(Thread::interrupt);
    }

    @Override
    public User getUser() {
        return taskView.getUser();
    }

    private void progress(double done) {
        if (updateCallback != null) {
            updateCallback.apply(done);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
```

Add `import static java.util.Optional.ofNullable;`. `builder.skipped()` is read only after the try-with-resources closes, because the stream is lazy and the counters are complete only once it has been drained.

- [ ] **Step 5: Run the test to verify it passes**

```bash
mvn -q -pl datashare-app -am test -Dtest=StructuredEntityExtractionTaskTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. Elasticsearch has to be reachable at `elasticsearch:9200`.

- [ ] **Step 6: Commit the happy path**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tasks/StructuredEntityExtractionTask.java datashare-app/src/test/java/org/icij/datashare/tasks/
git commit -m "feat(tasks): run an extraction mapping into statements and the entities index"
```

- [ ] **Step 7: Write the refusal and correction tests**

Add to `StructuredEntityExtractionTaskTest`:

```java
@Test
public void test_refuses_a_mapping_the_project_does_not_hold() throws Exception {
    source("companies.csv", "text/csv", "id,name\n1,ACME\n");
    when(mappings.get("prj", "missing")).thenReturn(Optional.empty());

    assertThat(assertThrows(IllegalArgumentException.class, () -> task("missing").call()).getMessage())
            .contains("no mapping 'missing'");
}

@Test
public void test_refuses_a_project_the_user_is_not_granted() throws Exception {
    source("companies.csv", "text/csv", "id,name\n1,ACME\n");
    stored(mapping("m1"));
    Task<StructuredEntityExtractionResult> taskView = new Task<>(
            StructuredEntityExtractionTask.class.getName(), User.localUser("bob", List.of("other")),
            Map.of("defaultProject", "prj", "mappingId", "m1"));

    assertThat(assertThrows(IllegalArgumentException.class,
                            () -> new StructuredEntityExtractionTask(indexer, statements, mappings,
                                                                     new PropertiesProvider(), taskView, null).call())
                       .getMessage()).contains("is not granted prj");
}

@Test
public void test_a_correction_supersedes_the_statements_the_previous_mapping_wrote() throws Exception {
    source("companies.csv", "text/csv", "id,name\n1,ACME\n2,Globex\n");
    stored(mapping("m1"));
    task("m1").call();
    source("companies.csv", "text/csv", "id,name\n1,ACME\n");
    stored(mapping("m2"));

    StructuredEntityExtractionResult corrected = task("m2").call();

    assertThat(corrected.retracted()).isEqualTo(2);
    assertThat(corrected.written()).isEqualTo(1);
    assertThat(statements.stored).hasSize(1);
}
```

`mapping("m2")` reuses the same helper, so the two mappings differ only by id, which is what makes the second a correction of the first.

- [ ] **Step 8: Write the four Review Focus tests this task owns**

```java
@Test
public void test_refuses_a_project_id_before_it_writes_anything() throws Exception {
    source("companies.csv", "text/csv", "id,name\n1,ACME\n");
    ExtractionMapping mapping = new ExtractionMapping("m1", "bad/name", null, "companies", "ftm", "docId",
            RowSourceOptions.defaults(),
            Map.of("c", new ExtractionMapping.EntityMapping("Company", List.of("id"),
                    Map.of("name", new ExtractionMapping.PropertyMapping(List.of("name"), null, null, null, null)))));
    when(mappings.get("bad/name", "m1")).thenReturn(Optional.of(mapping));
    Task<StructuredEntityExtractionResult> taskView = new Task<>(
            StructuredEntityExtractionTask.class.getName(), User.localUser("jane", List.of("bad/name")),
            Map.of("defaultProject", "bad/name", "mappingId", "m1"));

    assertThrows(IllegalArgumentException.class,
                 () -> new StructuredEntityExtractionTask(indexer, statements, mappings, new PropertiesProvider(),
                                                          taskView, null).call());
    assertThat(statements.stored).isEmpty();
}

@Test
public void test_the_task_id_fits_the_run_id_column() {
    Task<StructuredEntityExtractionResult> taskView = new Task<>(
            StructuredEntityExtractionTask.class.getName(), User.local(), Map.of());

    assertThat(taskView.getId().length()).isLessThan(97);
}

@Test
public void test_a_source_with_no_data_row_is_a_clean_run() throws Exception {
    source("companies.csv", "text/csv", "id,name\n");
    stored(mapping("m1"));

    StructuredEntityExtractionResult result = task("m1").call();

    assertThat(result.rows()).isEqualTo(0L);
    assertThat(result.written()).isEqualTo(0);
    assertThat(result.retracted()).isEqualTo(0);
    assertThat(result.indexed()).isEqualTo(0);
}

@Test
public void test_an_unsupported_content_type_names_the_readers_that_exist() throws Exception {
    source("scan.pdf", "application/pdf", "not a table");
    stored(mapping("m1"));

    assertThat(assertThrows(IllegalArgumentException.class, () -> task("m1").call()).getMessage())
            .contains("no reader supports application/pdf");
}
```

The `run_id` column is `varchar(96)` (changeset `047`) and this class's task id is 92 characters, so the assertion is what keeps a longer class name from overflowing it silently.

- [ ] **Step 9: Write the cancellation test**

```java
@Test
public void test_a_cancelled_run_throws_rather_than_writing_a_truncated_sheet() throws Exception {
    source("companies.csv", "text/csv", "id,name\n1,ACME\n2,Globex\n");
    stored(mapping("m1"));
    StructuredEntityExtractionTask task = task("m1");

    try {
        Thread.currentThread().interrupt();
        assertThrows(IllegalStateException.class, task::call);
    } finally {
        Thread.interrupted();
    }

    assertThat(statements.stored).isEmpty();
    assertThat(indexer.exists(Project.entitiesIndex("prj"))).isFalse();
}
```

`Thread.interrupted()` in the `finally` both tests and clears the flag, so the interrupt does not leak onto the next test running on the same thread. The index assertion is the point of the test: the rebuild must not run on a half-written sheet.

- [ ] **Step 10: Run the whole task test class**

```bash
mvn -q -pl datashare-app -am test -Dtest=StructuredEntityExtractionTaskTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS, ten tests.

- [ ] **Step 11: Commit**

```bash
git add datashare-app/src/test/java/org/icij/datashare/tasks/StructuredEntityExtractionTaskTest.java
git commit -m "test(tasks): pin the extraction task's refusals and its re-run behaviour"
```

---

### Task 5: factory and persistence wiring

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/RepositoryFactory.java`
- Modify: `datashare-db/src/main/java/org/icij/datashare/db/RepositoryFactoryImpl.java:41-60`
- Modify: `datashare-app/src/main/java/org/icij/datashare/mode/CommonMode.java:550-574`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/DatashareTaskFactory.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/DatashareTaskFactoryTest.java`

**Interfaces:**
- Consumes: `StructuredEntityExtractionTask` from Task 4, `StructuredEntityExtractionResult` from Task 3.
- Produces: nothing later tasks depend on. This is the last task.

- [ ] **Step 1: Write the failing test**

The reflection lookup in `TaskFactoryHelper.java:16` is what makes a naming mistake a runtime failure, so this test performs that exact lookup.

`datashare-app/src/test/java/org/icij/datashare/tasks/DatashareTaskFactoryTest.java` (create it if it does not exist):

```java
package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.Task;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.function.Function;

import static org.fest.assertions.Assertions.assertThat;

public class DatashareTaskFactoryTest {
    @Test
    public void test_the_factory_method_matches_the_task_class_simple_name() throws Exception {
        Method method = DatashareTaskFactory.class.getMethod(
                "create" + StructuredEntityExtractionTask.class.getSimpleName(), Task.class, Function.class);

        assertThat(method.getReturnType()).isEqualTo(StructuredEntityExtractionTask.class);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
mvn -q -pl datashare-app -am test -Dtest=DatashareTaskFactoryTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL with `NoSuchMethodException: createStructuredEntityExtractionTask`.

- [ ] **Step 3: Add the factory method**

In `datashare-app/src/main/java/org/icij/datashare/tasks/DatashareTaskFactory.java`, after `createLanguageDetectTask`:

```java
    StructuredEntityExtractionTask createStructuredEntityExtractionTask(
            Task<StructuredEntityExtractionResult> taskView, Function<Double, Void> updateCallback);
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
mvn -q -pl datashare-app -am test -Dtest=DatashareTaskFactoryTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 5: Add the repository factory methods**

In `datashare-api/src/main/java/org/icij/datashare/RepositoryFactory.java`:

```java
    StatementRepository createStatementRepository();

    ExtractionMappingRepository createExtractionMappingRepository();
```

with `import org.icij.datashare.model.StatementRepository;` and `import org.icij.datashare.tabular.ExtractionMappingRepository;`.

In `datashare-db/src/main/java/org/icij/datashare/db/RepositoryFactoryImpl.java`, after `createCasbinRuleRepository`:

```java
    @Override
    public StatementRepository createStatementRepository() {
        return createRepository(JooqStatementRepository::new);
    }

    @Override
    public ExtractionMappingRepository createExtractionMappingRepository() {
        return createRepository(JooqExtractionMappingRepository::new);
    }
```

Both Jooq classes already take `(DataSource, SQLDialect)`, which is exactly the `BiFunction` the private `createRepository` helper applies.

- [ ] **Step 6: Bind them**

In `datashare-app/src/main/java/org/icij/datashare/mode/CommonMode.java`, inside `configurePersistence`, next to the existing binds:

```java
        bind(StatementRepository.class).toInstance(repositoryFactory.createStatementRepository());
        bind(ExtractionMappingRepository.class).toInstance(repositoryFactory.createExtractionMappingRepository());
```

with the two matching imports.

- [ ] **Step 7: Build every module and run the datashare-app suite**

```bash
mvn -q -pl datashare-app -am test-compile
mvn -pl datashare-app -am test 2>&1 | tail -40
```

Expected: the module compiles and the suite is green apart from the four classes that fail for environment reasons on any branch. Separate those from anything this branch introduced before reporting.

- [ ] **Step 8: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/RepositoryFactory.java datashare-db/src/main/java/org/icij/datashare/db/RepositoryFactoryImpl.java datashare-app/src/main/java/org/icij/datashare/mode/CommonMode.java datashare-app/src/main/java/org/icij/datashare/tasks/DatashareTaskFactory.java datashare-app/src/test/java/org/icij/datashare/tasks/DatashareTaskFactoryTest.java
git commit -m "feat(tasks): wire the structured entity extraction task and its repositories"
```

---

## Suggested follow-ups

Out of scope here, worth their own issues:

- A `mapping_id` on the statement row, so two mappings can share one document and sheet without retracting each other.
- Incremental entities-index maintenance, if full rebuilds per run become the bottleneck.
- Promoting `InMemoryStatementRepository` over the private fake in `EntitiesIndexRebuilderTest`, once a third test needs one.
