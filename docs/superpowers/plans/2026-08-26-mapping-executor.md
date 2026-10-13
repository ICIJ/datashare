# Mapping executor (#2204) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn a persisted `ExtractionMapping` plus a file's `Row`s into `Statement`s, with a deterministic entity id and the raw value kept when a format changed it.

**Architecture:** One stateless-per-run class, `MappingExecutor`, in `datashare-api`'s `tabular` package: `Row` in, `List<Statement>` out, no I/O. Per row it runs two passes, first computing every entity's id from its key values so intra-row references resolve, then emitting one statement per property value. `Statement` gains a nullable `originalValue` outside its id hash, and changeset `048` adds the column that stores it.

**Tech Stack:** Java 17 records, JUnit 4, fest-assert (`org.fest.assertions.Assertions.assertThat`), Liquibase YAML changesets, jOOQ (generated at build, not committed), Maven multi-module.

**Spec:** `docs/superpowers/specs/2026-08-26-mapping-executor-design.md`

## Global Constraints

- Branch: `feat/2204-mapping-executor`, already created off `feat/2202-statement-store` (PR #2359). Do not rebase onto `main`.
- Test style: JUnit 4 (`org.junit.Test`), fest-assert, `test_snake_case_names`, matching `StatementTest` and `ExtractionMappingTest`.
- Never block on Maven. Run test commands in the background and poll, reporting each check.
- `-Dtest=` takes a **comma**-separated list. A `+` separator silently matches nothing and reports green. Never drop `-am`.
- Zero comments by default. Write one only for a non-obvious *why*, matching the density of the surrounding files.
- Commit with the `/commit` skill, one commit per task, semantic message, no Claude attribution.
- Four test classes fail on this box for environment reasons on any branch. Compare against the base branch before blaming a change.
- `docs/superpowers/` is gitignored: never `git add` the spec or this plan.

Test commands used throughout:

```bash
mvn -pl datashare-api -am test -Dtest=MappingExecutorTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl datashare-app -am test -Dtest=MappingExecutionTest -Dsurefire.failIfNoSpecifiedTests=false
make reset-db && mvn -pl datashare-db -am test -Dtest=JooqStatementRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false
```

---

### Task 1: `Statement.originalValue`

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/model/Statement.java`
- Test: `datashare-api/src/test/java/org/icij/datashare/model/StatementTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `Statement(String id, String model, String entityId, String entityType, String property, String value, String originalValue, Provenance provenance)`, `Statement.withOriginalValue(String raw) -> Statement`. `Statement.of(...)` keeps its six-argument signature and yields a null `originalValue`.

- [ ] **Step 1: Write the failing tests**

Add to `StatementTest`, and add `import static org.junit.Assert.assertThrows;` to its imports (the file imports only `fail` today):

```java
    @Test
    public void test_a_statement_has_no_original_value_by_default() {
        assertThat(Statement.of("ftm", "person-1", "Person", "name", "Jane Doe", provenance).originalValue())
                .isNull();
    }

    @Test
    public void test_the_original_value_does_not_change_the_id() {
        Statement statement = Statement.of("ftm", "person-1", "Person", "birthDate", "1970-01-01", provenance);

        assertThat(statement.withOriginalValue("01/01/1970").id()).isEqualTo(statement.id());
        assertThat(statement.withOriginalValue("01/01/1970").originalValue()).isEqualTo("01/01/1970");
    }

    @Test
    public void test_a_nul_in_the_original_value_is_rejected() {
        Statement statement = Statement.of("ftm", "person-1", "Person", "name", "Jane Doe", provenance);

        assertThrows(IllegalArgumentException.class, () -> statement.withOriginalValue("Jane\u0000Doe"));
    }
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `mvn -pl datashare-api -am test -Dtest=StatementTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol: method originalValue()` and `withOriginalValue(String)`.

- [ ] **Step 3: Add the component and the wither**

In `Statement.java`, add `String originalValue` between `value` and `provenance` in the record header, then in the compact constructor, after the `value` line:

```java
        originalValue = originalValue == null ? null : component(originalValue, "originalValue");
```

Update the single `new Statement(...)` inside `of(...)` to pass `null` in the new position, and add:

```java
    /** The value as it was read, kept when a format changed it. Outside the id hash: the same fact
     *  keeps the same id whether or not the raw value was recorded. */
    public Statement withOriginalValue(String raw) {
        return new Statement(id, model, entityId, entityType, property, value, raw, provenance);
    }
```

Leave `id(...)` untouched: nine components, no `originalValue`.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `mvn -pl datashare-api -am test -Dtest=StatementTest,ModelEntityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. `ModelEntityTest` is in the list because it builds statements too and must still compile.

- [ ] **Step 5: Commit**

`/commit` with: `feat(model): keep the value as it was read alongside the formatted one`

---

### Task 2: changeset 048 and the `original_value` column

**Files:**
- Create: `datashare-db/src/main/resources/liquibase/changelog/changes/048-add-statement-original-value.yml`
- Modify: `datashare-db/src/main/resources/liquibase/changelog/db.changelog.yml` (append an include at the end)
- Modify: `datashare-db/src/main/java/org/icij/datashare/db/JooqStatementRepository.java:41-43` (`READ_FIELDS`), its `row(...)` and its `toStatement(...)`
- Test: `datashare-db/src/test/java/org/icij/datashare/db/JooqStatementRepositoryTest.java`

**Interfaces:**
- Consumes: `Statement.originalValue()` and `Statement.withOriginalValue` from Task 1.
- Produces: a `statement.original_value` column, nullable text, read back into `Statement.originalValue()`.

- [ ] **Step 1: Write the failing tests**

Add to `JooqStatementRepositoryTest` (its `statement(...)` helper already exists):

```java
    @Test
    public void test_save_writes_the_original_value_when_a_format_changed_it() {
        repository.save("prj", "run-1", List.of(statement("entity-1", "Person", "birthDate", "1970-01-01")
                .withOriginalValue("01/01/1970")));

        assertThat(dbRule.dsl().select(STATEMENT.ORIGINAL_VALUE).from(STATEMENT).fetchOne().value1())
                .isEqualTo("01/01/1970");
    }

    @Test
    public void test_a_statement_no_format_touched_stores_no_original_value() {
        repository.save("prj", "run-1", List.of(statement("entity-1", "Person", "name", "Jane Doe")));

        assertThat(dbRule.dsl().select(STATEMENT.ORIGINAL_VALUE).from(STATEMENT).fetchOne().value1()).isNull();
    }

    @Test
    public void test_a_statement_with_an_original_value_still_rebuilds_its_entity() {
        repository.save("prj", "run-1", List.of(statement("entity-1", "Person", "birthDate", "1970-01-01")
                .withOriginalValue("01/01/1970")));

        assertThat(repository.entity("prj", "entity-1").orElseThrow().properties().get("birthDate"))
                .containsExactly("1970-01-01");
    }
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `mvn -pl datashare-db -am test -Dtest=JooqStatementRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol: STATEMENT.ORIGINAL_VALUE` (the jOOQ class has no such field yet).

- [ ] **Step 3: Write the changeset**

`048-add-statement-original-value.yml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 83
      author: pirhoo
      changes:
        - addColumn:
            tableName: statement
            columns:
              - column:
                  name: original_value
                  type: text
```

Changeset id `83` because `047` used `81` and `82`. Nullable on purpose: null means the value is the raw value, so the common case costs nothing.

Append to `db.changelog.yml`, after the `047` include:

```yaml
  - include:
      file: changes/048-add-statement-original-value.yml
      relativeToChangelogFile: true
```

- [ ] **Step 4: Regenerate the jOOQ classes**

Run: `make reset-db`
Expected: Liquibase applies 81, 82 and 83; `StatementRecord` gains `getOriginalValue`/`setOriginalValue` at the next build. Generated sources are not committed.

- [ ] **Step 5: Wire the column into the repository**

In `READ_FIELDS`, add `STATEMENT.ORIGINAL_VALUE` after `STATEMENT.VALUE`. In `row(Write, Statement)`, after `row.setValue(...)`:

```java
        row.setOriginalValue(statement.originalValue());
```

**Call it unconditionally, including when the value is null.** `saveChunk` builds one insert template from `chunk.get(0)`'s changed fields and then binds every row's `intoArray()` against it, so a column set on some rows and skipped on others makes the arity drift.

In `toStatement(Record)`, pass the column in the new position:

```java
        return new Statement(row.get(STATEMENT.ID), model, row.get(STATEMENT.ENTITY_ID),
                row.get(STATEMENT.ENTITY_TYPE), property.substring(prefix.length()), row.get(STATEMENT.VALUE),
                row.get(STATEMENT.ORIGINAL_VALUE),
                new Statement.Provenance(row.get(STATEMENT.DOC_ID), row.get(STATEMENT.SHEET),
                        row.get(STATEMENT.ROW_NUMBER), row.get(STATEMENT.COLUMN_NAME)));
```

- [ ] **Step 6: Run the tests and watch them pass**

Run: `mvn -pl datashare-db -am test -Dtest=JooqStatementRepositoryTest,JooqExtractionMappingRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS on both SQLite and Postgres parameters.

- [ ] **Step 7: Commit**

`/commit` with: `feat(db): store the value as it was read when a format changed it`

---

### Task 3: `MappingExecutor`, the entity id and single-column properties

**Files:**
- Create: `datashare-api/src/main/java/org/icij/datashare/tabular/MappingExecutor.java`
- Test: `datashare-api/src/test/java/org/icij/datashare/tabular/MappingExecutorTest.java`

**Interfaces:**
- Consumes: `ExtractionMapping`, `Row`, `Statement.of`, `TargetModelRegistry.get`, `Hasher.SHA_384.hash`.
- Produces: `new MappingExecutor(ExtractionMapping)`, `List<Statement> statements(Row row)`. Later tasks add `long skipped()` and keep both signatures unchanged.

- [ ] **Step 1: Write the failing tests**

Create `MappingExecutorTest` with the fixtures every later task reuses:

```java
package org.icij.datashare.tabular;

import org.icij.datashare.model.Statement;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class MappingExecutorTest {

    static ExtractionMapping mapping(Map<String, ExtractionMapping.EntityMapping> entities) {
        return new ExtractionMapping("map-1", "prj", "jdoe", "staff", "ftm", "doc-1",
                RowSourceOptions.defaults(), entities);
    }

    static ExtractionMapping.EntityMapping entity(String type, List<String> keys,
                                                  Map<String, ExtractionMapping.PropertyMapping> properties) {
        return new ExtractionMapping.EntityMapping(type, keys, properties);
    }

    static ExtractionMapping.PropertyMapping column(String name) {
        return new ExtractionMapping.PropertyMapping(List.of(name), null, null, null, null);
    }

    static Row row(Map<String, String> values) {
        return new Row(7L, values);
    }

    private static MappingExecutor person(List<String> keys, Map<String, ExtractionMapping.PropertyMapping> props) {
        return new MappingExecutor(mapping(Map.of("member", entity("Person", keys, props))));
    }

    @Test
    public void test_a_column_property_becomes_one_statement_carrying_its_cell() {
        List<Statement> statements = person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe")));

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).model()).isEqualTo("ftm");
        assertThat(statements.get(0).entityType()).isEqualTo("Person");
        assertThat(statements.get(0).property()).isEqualTo("name");
        assertThat(statements.get(0).value()).isEqualTo("Jane Doe");
        assertThat(statements.get(0).originalValue()).isNull();
    }

    @Test
    public void test_a_statement_carries_the_document_the_sheet_the_row_and_the_column() {
        Statement statement = person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe"))).get(0);

        assertThat(statement.provenance().documentId()).isEqualTo("doc-1");
        assertThat(statement.provenance().sheet()).isEqualTo("");
        assertThat(statement.provenance().rowNumber()).isEqualTo(7L);
        assertThat(statement.provenance().column()).isEqualTo("full_name");
    }

    @Test
    public void test_two_mappings_naming_the_key_column_differently_agree_on_the_entity_id() {
        String one = person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe"))).get(0).entityId();
        String two = person(List.of("passport_no"), Map.of("name", column("name")))
                .statements(row(Map.of("passport_no", "AB123", "name", "Jane Doe"))).get(0).entityId();

        assertThat(one).isEqualTo(two);
    }

    @Test
    public void test_the_order_the_keys_are_declared_in_does_not_change_the_entity_id() {
        Map<String, String> cells = Map.of("passport", "AB123", "country", "FR", "full_name", "Jane Doe");
        String one = person(List.of("passport", "country"), Map.of("name", column("full_name")))
                .statements(row(cells)).get(0).entityId();
        String two = person(List.of("country", "passport"), Map.of("name", column("full_name")))
                .statements(row(cells)).get(0).entityId();

        assertThat(one).isEqualTo(two);
    }

    @Test
    public void test_surrounding_whitespace_in_a_key_does_not_split_the_entity() {
        String one = person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", " AB123 ", "full_name", "Jane Doe"))).get(0).entityId();
        String two = person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe"))).get(0).entityId();

        assertThat(one).isEqualTo(two);
    }

    @Test
    public void test_two_types_keyed_on_the_same_value_are_two_entities() {
        String person = person(List.of("id"), Map.of("name", column("n")))
                .statements(row(Map.of("id", "AB123", "n", "Jane Doe"))).get(0).entityId();
        String company = new MappingExecutor(mapping(Map.of("employer",
                entity("Company", List.of("id"), Map.of("name", column("n"))))))
                .statements(row(Map.of("id", "AB123", "n", "Jane Doe"))).get(0).entityId();

        assertThat(person).isNotEqualTo(company);
    }

    @Test
    public void test_a_row_whose_key_is_blank_yields_no_statement() {
        assertThat(person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", "  ", "full_name", "Jane Doe")))).isEmpty();
    }

    @Test
    public void test_a_blank_cell_yields_no_statement() {
        assertThat(person(List.of("passport"), Map.of("name", column("full_name"), "email", column("mail")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe", "mail", "")))).hasSize(1);
    }
}
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `mvn -pl datashare-api -am test -Dtest=MappingExecutorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol: class MappingExecutor`.

- [ ] **Step 3: Write the minimal implementation**

```java
package org.icij.datashare.tabular;

import org.icij.datashare.model.Statement;
import org.icij.datashare.model.TargetModel;
import org.icij.datashare.model.TargetModelRegistry;
import org.icij.datashare.text.Hasher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Turns a row into the statements a mapping asks for. One instance per run: rows are consumed on one
 * thread, and nothing here reads or writes anything, so the caller owns the source and the store.
 */
public class MappingExecutor {
    private final ExtractionMapping mapping;
    private final TargetModel target;

    public MappingExecutor(ExtractionMapping mapping) {
        this.mapping = mapping;
        this.target = TargetModelRegistry.get(mapping.model());
    }

    public List<Statement> statements(Row row) {
        Map<String, String> ids = identify(row);
        List<Statement> statements = new ArrayList<>();
        for (String alias : new TreeSet<>(ids.keySet())) {
            statements.addAll(statementsOf(alias, ids.get(alias), row));
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

    private List<Statement> statementsOf(String alias, String entityId, Row row) {
        ExtractionMapping.EntityMapping entity = mapping.entities().get(alias);
        List<Statement> statements = new ArrayList<>();
        for (String property : new TreeSet<>(entity.properties().keySet())) {
            for (String column : entity.properties().get(property).columns()) {
                add(statements, entityId, entity.type(), property, cell(row, column), column, row);
            }
        }
        return statements;
    }

    private void add(List<Statement> statements, String entityId, String type, String property,
                     String value, String column, Row row) {
        if (value == null || value.isBlank()) {
            return;
        }
        statements.add(Statement.of(mapping.model(), entityId, type, property, value,
                new Statement.Provenance(mapping.documentId(), mapping.options().sheet(), row.number(), column)));
    }

    private static String cell(Row row, String column) {
        return row.values().getOrDefault(column, "");
    }
}
```

`target` is unused until Task 6; keep the field, the constructor line is what makes the model resolvable then. Aliases and property names are walked through a `TreeSet` because `entities()` and `properties()` are `Map.copyOf` maps with no stable iteration order, and `ExtractionMapping.validate` already sets that precedent.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `mvn -pl datashare-api -am test -Dtest=MappingExecutorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

`/commit` with: `feat(tabular): map a row onto entities identified by their key values`

---

### Task 4: literals, references, multi-column and joined properties

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/tabular/MappingExecutor.java`
- Test: `datashare-api/src/test/java/org/icij/datashare/tabular/MappingExecutorTest.java`

**Interfaces:**
- Consumes: Task 3's `statements(Row)`.
- Produces: no new method. `statementsOf` now branches on `literal`, `entity`, `join` and plain columns.

- [ ] **Step 1: Write the failing tests**

Add to `MappingExecutorTest`, plus two fixtures next to `column(...)`:

```java
    static ExtractionMapping.PropertyMapping literal(String value) {
        return new ExtractionMapping.PropertyMapping(List.of(), null, value, null, null);
    }

    static ExtractionMapping.PropertyMapping reference(String alias) {
        return new ExtractionMapping.PropertyMapping(List.of(), null, null, alias, null);
    }

    static ExtractionMapping.PropertyMapping joined(List<String> columns, String separator) {
        return new ExtractionMapping.PropertyMapping(columns, separator, null, null, null);
    }
```

```java
    @Test
    public void test_a_literal_property_is_stored_with_no_column_of_origin() {
        Statement statement = new MappingExecutor(mapping(Map.of("member", entity("Person", List.of("passport"),
                Map.of("name", column("full_name"), "nationality", literal("fr"))))))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe"))).stream()
                .filter(candidate -> candidate.property().equals("nationality")).findFirst().orElseThrow();

        assertThat(statement.value()).isEqualTo("fr");
        assertThat(statement.provenance().column()).isEqualTo("");
    }

    @Test
    public void test_several_columns_without_a_join_are_several_statements() {
        List<Statement> statements = new MappingExecutor(mapping(Map.of("member", entity("Person",
                List.of("passport"), Map.of("name", new ExtractionMapping.PropertyMapping(
                        List.of("full_name", "maiden_name"), null, null, null, null))))))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe", "maiden_name", "Jane Roe")));

        assertThat(statements).hasSize(2);
        assertThat(statements.stream().map(Statement::value).sorted().toList())
                .isEqualTo(List.of("Jane Doe", "Jane Roe"));
        assertThat(statements.stream().map(statement -> statement.provenance().column()).sorted().toList())
                .isEqualTo(List.of("full_name", "maiden_name"));
    }

    @Test
    public void test_a_join_concatenates_the_columns_into_one_statement() {
        Statement statement = new MappingExecutor(mapping(Map.of("member", entity("Person", List.of("passport"),
                Map.of("name", joined(List.of("first_name", "last_name"), " "))))))
                .statements(row(Map.of("passport", "AB123", "first_name", "Jane", "last_name", "Doe"))).get(0);

        assertThat(statement.value()).isEqualTo("Jane Doe");
        assertThat(statement.provenance().column()).isEqualTo("first_name,last_name");
    }

    @Test
    public void test_a_join_skips_a_blank_column_rather_than_doubling_the_separator() {
        Statement statement = new MappingExecutor(mapping(Map.of("member", entity("Person", List.of("passport"),
                Map.of("name", joined(List.of("first_name", "middle_name", "last_name"), " "))))))
                .statements(row(Map.of("passport", "AB123", "first_name", "Jane", "middle_name", "",
                        "last_name", "Doe"))).get(0);

        assertThat(statement.value()).isEqualTo("Jane Doe");
    }

    @Test
    public void test_a_reference_stores_the_id_of_the_entity_it_names() {
        List<Statement> statements = new MappingExecutor(mapping(Map.of(
                "member", entity("Person", List.of("passport"), Map.of("name", column("full_name"))),
                "employer", entity("Company", List.of("siren"), Map.of("name", column("company"))),
                "job", entity("Employment", List.of("passport", "siren"), Map.of(
                        "employee", reference("member"), "employer", reference("employer"))))))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe",
                        "siren", "552100554", "company", "ACME")));

        String person = statements.stream().filter(s -> s.entityType().equals("Person"))
                .findFirst().orElseThrow().entityId();
        Statement employee = statements.stream().filter(s -> s.property().equals("employee"))
                .findFirst().orElseThrow();

        assertThat(employee.value()).isEqualTo(person);
        assertThat(employee.entityType()).isEqualTo("Employment");
        assertThat(employee.provenance().column()).isEqualTo("");
    }

    @Test
    public void test_a_reference_to_an_unidentified_entity_yields_no_statement() {
        List<Statement> statements = new MappingExecutor(mapping(Map.of(
                "member", entity("Person", List.of("passport"), Map.of("name", column("full_name"))),
                "employer", entity("Company", List.of("siren"), Map.of("name", column("company"))),
                "job", entity("Employment", List.of("passport"), Map.of(
                        "employee", reference("member"), "employer", reference("employer"))))))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe",
                        "siren", "", "company", "ACME")));

        assertThat(statements.stream().filter(s -> s.property().equals("employer")).toList()).isEmpty();
    }
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `mvn -pl datashare-api -am test -Dtest=MappingExecutorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL. The literal, join and reference tests all report zero statements, because Task 3 only reads `columns()`.

- [ ] **Step 3: Branch on the four shapes**

Thread the row's id map into `statementsOf` and replace its body:

```java
    private List<Statement> statementsOf(String alias, String entityId, Row row, Map<String, String> ids) {
        ExtractionMapping.EntityMapping entity = mapping.entities().get(alias);
        List<Statement> statements = new ArrayList<>();
        for (String property : new TreeSet<>(entity.properties().keySet())) {
            ExtractionMapping.PropertyMapping mapped = entity.properties().get(property);
            if (mapped.literal() != null) {
                add(statements, entityId, entity.type(), property, mapped.literal(), "", row);
            } else if (mapped.entity() != null) {
                add(statements, entityId, entity.type(), property, ids.get(mapped.entity()), "", row);
            } else if (mapped.join() != null) {
                add(statements, entityId, entity.type(), property, mapped.columns().stream()
                                .map(column -> cell(row, column)).filter(cell -> !cell.isBlank())
                                .collect(joining(mapped.join())),
                        String.join(",", mapped.columns()), row);
            } else {
                for (String column : mapped.columns()) {
                    add(statements, entityId, entity.type(), property, cell(row, column), column, row);
                }
            }
        }
        return statements;
    }
```

Add `import static java.util.stream.Collectors.joining;`, and update the caller in `statements(Row)` to `statementsOf(alias, ids.get(alias), row, ids)`.

No re-check that exactly one of `columns`, `literal` and `entity` is set: `PropertyMapping`'s own constructor already guarantees it (`ExtractionMapping.java:44-58`). `add` returning early on a null value is what drops a reference to an entity pass 1 could not identify.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `mvn -pl datashare-api -am test -Dtest=MappingExecutorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 14 tests.

- [ ] **Step 5: Commit**

`/commit` with: `feat(tabular): fill a property from a literal, a reference or several columns`

---

### Task 5: `format` and the value as it was read

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/tabular/MappingExecutor.java`
- Test: `datashare-api/src/test/java/org/icij/datashare/tabular/MappingExecutorTest.java`

**Interfaces:**
- Consumes: Task 1's `Statement.withOriginalValue`, Task 4's `statementsOf`.
- Produces: no new public method. `add` takes a `Value(String value, String original)` instead of a bare string.

- [ ] **Step 1: Write the failing tests**

Add a fixture and the tests:

```java
    static ExtractionMapping.PropertyMapping formatted(String name, String pattern) {
        return new ExtractionMapping.PropertyMapping(List.of(name), null, null, null, pattern);
    }
```

```java
    @Test
    public void test_a_formatted_column_is_stored_as_iso_with_the_input_kept() {
        Statement statement = new MappingExecutor(mapping(Map.of("member", entity("Person", List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "dd/MM/yyyy"))))))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe", "born", "01/03/1970")))
                .stream().filter(candidate -> candidate.property().equals("birthDate")).findFirst().orElseThrow();

        assertThat(statement.value()).isEqualTo("1970-03-01");
        assertThat(statement.originalValue()).isEqualTo("01/03/1970");
    }

    @Test
    public void test_a_value_that_does_not_parse_is_stored_as_it_was_read() {
        Statement statement = new MappingExecutor(mapping(Map.of("member", entity("Person", List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "dd/MM/yyyy"))))))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe", "born", "n/a")))
                .stream().filter(candidate -> candidate.property().equals("birthDate")).findFirst().orElseThrow();

        assertThat(statement.value()).isEqualTo("n/a");
        assertThat(statement.originalValue()).isNull();
    }

    @Test
    public void test_a_value_already_in_the_target_form_keeps_no_original() {
        Statement statement = new MappingExecutor(mapping(Map.of("member", entity("Person", List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "yyyy-MM-dd"))))))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe", "born", "1970-03-01")))
                .stream().filter(candidate -> candidate.property().equals("birthDate")).findFirst().orElseThrow();

        assertThat(statement.value()).isEqualTo("1970-03-01");
        assertThat(statement.originalValue()).isNull();
    }

    @Test
    public void test_a_malformed_pattern_fails_at_construction_not_at_the_first_row() {
        assertThrows(IllegalArgumentException.class, () -> new MappingExecutor(mapping(Map.of("member",
                entity("Person", List.of("passport"),
                        Map.of("name", column("full_name"), "birthDate", formatted("born", "not-a-pattern")))))));
    }
```

Add `import static org.junit.Assert.assertThrows;` to the test's imports.

- [ ] **Step 2: Run the tests and watch them fail**

Run: `mvn -pl datashare-api -am test -Dtest=MappingExecutorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL. `format` is ignored, so the first test reports `01/03/1970` as the value and the fourth throws nothing.

- [ ] **Step 3: Parse in the executor, compile the patterns in the constructor**

Add to the field block and the constructor:

```java
    private final Map<String, DateTimeFormatter> formats = new HashMap<>();
```

```java
        mapping.entities().values().forEach(entity -> entity.properties().values().stream()
                .map(ExtractionMapping.PropertyMapping::format).filter(Objects::nonNull)
                .forEach(format -> formats.computeIfAbsent(format, DateTimeFormatter::ofPattern)));
```

Add the value pair and the parser:

```java
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
```

Change `add` to take a `Value`:

```java
    private void add(List<Statement> statements, String entityId, String type, String property,
                     Value value, String column, Row row) {
        if (value.value() == null || value.value().isBlank()) {
            return;
        }
        Statement statement = Statement.of(mapping.model(), entityId, type, property, value.value(),
                new Statement.Provenance(mapping.documentId(), mapping.options().sheet(), row.number(), column));
        statements.add(value.original() == null ? statement : statement.withOriginalValue(value.original()));
    }
```

Update the four call sites in `statementsOf`: literal and reference pass `new Value(mapped.literal(), null)` and `new Value(ids.get(mapped.entity()), null)`; the join and column branches pass `value(<the cell>, mapped.format())`. A `format` alongside a `literal` or an `entity` is impossible, `PropertyMapping` rejects it (`ExtractionMapping.java:55-57`).

Imports to add: `java.time.LocalDate`, `java.time.format.DateTimeFormatter`, `java.time.format.DateTimeParseException`, `java.util.HashMap`, `java.util.Objects`.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `mvn -pl datashare-api -am test -Dtest=MappingExecutorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 18 tests.

- [ ] **Step 5: Commit**

`/commit` with: `feat(tabular): read a date column through its pattern and keep the input`

---

### Task 6: model checks, the skipped count and the missing-column guard

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/tabular/MappingExecutor.java`
- Test: `datashare-api/src/test/java/org/icij/datashare/tabular/MappingExecutorTest.java`

**Interfaces:**
- Consumes: `TargetModel.validate(ModelEntity)`, `ModelEntity.from(Collection<Statement>)`, `InvalidExtractionMapping(String id, List<TargetModel.Violation>)`.
- Produces: `long skipped()`, the last piece of the public surface #2206 consumes.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    public void test_an_entity_missing_a_required_property_is_dropped_and_counted() {
        MappingExecutor executor = new MappingExecutor(mapping(Map.of("member",
                entity("Person", List.of("passport"), Map.of("name", column("full_name"))))));

        assertThat(executor.statements(row(Map.of("passport", "AB123", "full_name", "")))).isEmpty();
        assertThat(executor.skipped()).isEqualTo(1L);
    }

    @Test
    public void test_an_edge_that_lost_an_endpoint_is_dropped_but_the_entities_are_kept() {
        MappingExecutor executor = new MappingExecutor(mapping(Map.of(
                "member", entity("Person", List.of("passport"), Map.of("name", column("full_name"))),
                "employer", entity("Company", List.of("siren"), Map.of("name", column("company"))),
                "job", entity("Employment", List.of("passport"), Map.of(
                        "employee", reference("member"), "employer", reference("employer"))))));

        List<Statement> statements = executor.statements(row(Map.of("passport", "AB123",
                "full_name", "Jane Doe", "siren", "", "company", "")));

        assertThat(statements.stream().map(Statement::entityType).distinct().toList()).isEqualTo(List.of("Person"));
        assertThat(executor.skipped()).isEqualTo(1L);
    }

    @Test
    public void test_a_mapping_that_no_longer_validates_fails_at_construction() {
        ExtractionMapping stale = mapping(Map.of("member",
                entity("Person", List.of("passport"), Map.of("hoofSize", column("hooves")))));

        InvalidExtractionMapping thrown =
                assertThrows(InvalidExtractionMapping.class, () -> new MappingExecutor(stale));
        assertThat(thrown.violations.toString()).contains("hoofSize");
    }

    @Test
    public void test_a_column_the_source_does_not_have_fails_the_run() {
        MappingExecutor executor = new MappingExecutor(mapping(Map.of("member",
                entity("Person", List.of("passport"), Map.of("name", column("fullname"))))));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> executor.statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe"))));
        assertThat(thrown.getMessage()).contains("fullname");
    }
```

The second test is the spec's "a reference to a dropped entity is kept, not cascaded" decision seen from the other side: the `Employment` here is dropped by the model check because both endpoints are gone, while the `Person` survives.

- [ ] **Step 2: Run the tests and watch them fail**

Run: `mvn -pl datashare-api -am test -Dtest=MappingExecutorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure on `skipped()`, then failures: the nameless Person is emitted, the stale mapping constructs, the typo column reads as blank.

- [ ] **Step 3: Add the three checks**

Constructor, first statement, before assigning anything:

```java
        List<TargetModel.Violation> violations = mapping.validate();
        if (!violations.isEmpty()) {
            throw new InvalidExtractionMapping(mapping.id(), violations);
        }
```

`ExtractionMapping.validate` runs on save, not on read, so a mapping stored before the ontology moved still loads; checking here fails the run once instead of one row at a time.

Collect the mapped columns in the same constructor loop that compiles the formats:

```java
    private final Set<String> columns = new TreeSet<>();
    private boolean checked;
    private long skipped;
```

```java
        mapping.entities().values().forEach(entity -> {
            columns.addAll(entity.keys());
            entity.properties().values().forEach(property -> {
                columns.addAll(property.columns());
                if (property.format() != null) {
                    formats.computeIfAbsent(property.format(), DateTimeFormatter::ofPattern);
                }
            });
        });
```

(That loop replaces the stream added in Task 5.)

Then the guard, the counter and the model check:

```java
    /** Entities that were identified but dropped, either by the model or because they produced no
     *  statement at all. */
    public long skipped() {
        return skipped;
    }

    public List<Statement> statements(Row row) {
        requireColumns(row);
        Map<String, String> ids = identify(row);
        List<Statement> statements = new ArrayList<>();
        for (String alias : new TreeSet<>(ids.keySet())) {
            List<Statement> candidate = statementsOf(alias, ids.get(alias), row, ids);
            if (candidate.isEmpty() || !target.validate(ModelEntity.from(candidate)).isEmpty()) {
                skipped++;
            } else {
                statements.addAll(candidate);
            }
        }
        return statements;
    }

    // Row.values pads a short row with empty strings, so a column the file does not have reads like a
    // blank cell: without this, one typo imports every row as nothing and reports it as a success.
    private void requireColumns(Row row) {
        if (checked) {
            return;
        }
        List<String> missing = columns.stream().filter(column -> !row.values().containsKey(column)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("the source has no column " + missing + ", declared by mapping '"
                    + mapping.id() + "'");
        }
        checked = true;
    }
```

Imports to add: `org.icij.datashare.model.ModelEntity`, `java.util.Set`.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `mvn -pl datashare-api -am test -Dtest=MappingExecutorTest,ExtractionMappingTest,StatementTest,ModelEntityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 22 tests in `MappingExecutorTest` and no regression in the three neighbours.

- [ ] **Step 5: Commit**

`/commit` with: `feat(tabular): drop an entity the model rejects and refuse a column the source lacks`

---

### Task 7: a CSV and a mapping, end to end

**Files:**
- Create: `datashare-app/src/test/java/org/icij/datashare/tabular/MappingExecutionTest.java`

**Interfaces:**
- Consumes: `MappingExecutor`, `DelimitedRowSource.rows(InputStream, RowSourceOptions)`, `ModelEntity.from`.
- Produces: nothing. This is the issue's acceptance test.

- [ ] **Step 1: Write the failing test**

```java
package org.icij.datashare.tabular;

import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.Statement;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;
import static org.fest.assertions.Assertions.assertThat;

public class MappingExecutionTest {
    private static final String CSV = """
            person_id,full_name,born,company_id,company_name,job_title
            p-1,Jane Doe,01/03/1970,c-1,ACME,Director
            """;

    private static ExtractionMapping.PropertyMapping column(String name) {
        return new ExtractionMapping.PropertyMapping(List.of(name), null, null, null, null);
    }

    private static ExtractionMapping.PropertyMapping reference(String alias) {
        return new ExtractionMapping.PropertyMapping(List.of(), null, null, alias, null);
    }

    private static final ExtractionMapping MAPPING = new ExtractionMapping("map-1", "prj", "jdoe", "staff", "ftm",
            "doc-1", RowSourceOptions.defaults(), Map.of(
            "person", new ExtractionMapping.EntityMapping("Person", List.of("person_id"), Map.of(
                    "name", column("full_name"),
                    "birthDate", new ExtractionMapping.PropertyMapping(
                            List.of("born"), null, null, null, "dd/MM/yyyy"))),
            "company", new ExtractionMapping.EntityMapping("Company", List.of("company_id"), Map.of(
                    "name", column("company_name"))),
            "job", new ExtractionMapping.EntityMapping("Employment", List.of("person_id", "company_id"), Map.of(
                    "employee", reference("person"),
                    "employer", reference("company"),
                    "role", column("job_title")))));

    @Test
    public void test_a_csv_and_a_mapping_produce_two_entities_and_the_relationship_between_them() throws Exception {
        MappingExecutor executor = new MappingExecutor(MAPPING);
        List<Statement> statements;
        try (InputStream source = new ByteArrayInputStream(CSV.getBytes(UTF_8));
             Stream<Row> rows = new DelimitedRowSource().rows(source, RowSourceOptions.defaults())) {
            statements = rows.flatMap(row -> executor.statements(row).stream()).toList();
        }

        Map<String, ModelEntity> entities = statements.stream().collect(groupingBy(Statement::entityId))
                .values().stream().map(ModelEntity::from)
                .collect(toMap(entity -> entity.types().iterator().next(), entity -> entity));

        assertThat(entities.keySet()).contains("Person", "Company", "Employment");
        assertThat(entities.get("Person").properties().get("name")).containsExactly("Jane Doe");
        assertThat(entities.get("Person").properties().get("birthDate")).containsExactly("1970-03-01");
        assertThat(entities.get("Company").properties().get("name")).containsExactly("ACME");
        assertThat(entities.get("Employment").properties().get("role")).containsExactly("Director");
        assertThat(entities.get("Employment").properties().get("employee"))
                .containsExactly(entities.get("Person").id());
        assertThat(entities.get("Employment").properties().get("employer"))
                .containsExactly(entities.get("Company").id());
        assertThat(executor.skipped()).isEqualTo(0L);
    }

    @Test
    public void test_the_same_file_read_twice_produces_the_same_statement_ids() throws Exception {
        assertThat(run().stream().map(Statement::id).sorted().toList())
                .isEqualTo(run().stream().map(Statement::id).sorted().toList());
    }

    private List<Statement> run() throws Exception {
        MappingExecutor executor = new MappingExecutor(MAPPING);
        try (InputStream source = new ByteArrayInputStream(CSV.getBytes(UTF_8));
             Stream<Row> rows = new DelimitedRowSource().rows(source, RowSourceOptions.defaults())) {
            return rows.flatMap(row -> executor.statements(row).stream()).toList();
        }
    }
}
```

`role` resolves on `Employment` through its `Interest` ancestor, and `FtmTargetModel` flattens inherited properties into each type (`FtmTargetModel.java:140`), so the mapping validates.

- [ ] **Step 2: Run the test and watch it fail**

Run: `mvn -pl datashare-app -am test -Dtest=MappingExecutionTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL only if something in Tasks 3 to 6 is wrong. If it passes first try, delete one property from the mapping, confirm the assertion fails, and restore it: a test that has never been red is not a test.

- [ ] **Step 3: Fix whatever it caught**

No production code is planned here. If the test fails, the bug is in `MappingExecutor` and the fix belongs in it, with a unit test added to `MappingExecutorTest` reproducing it first.

- [ ] **Step 4: Run the whole tabular package**

Run: `mvn -pl datashare-app -am test -Dtest=MappingExecutionTest,DelimitedRowSourceTest,WorkbookRowSourceTest,JsonRowSourceTest,TikaTableRowSourceTest,RowSourceEquivalenceTest,TabularRowReaderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

`/commit` with: `test(tabular): run a csv and a mapping into two entities and their employment`

---

### Task 8: the executor's output is idempotent in the store

**Files:**
- Modify: `datashare-db/src/test/java/org/icij/datashare/db/JooqStatementRepositoryTest.java`

**Interfaces:**
- Consumes: `MappingExecutor`, `Row`, `StatementRepository.save`. `datashare-db` already depends on `datashare-api`, and nothing here needs a reader, so no new module dependency.
- Produces: nothing.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    public void test_saving_the_same_row_twice_writes_it_once() {
        MappingExecutor executor = new MappingExecutor(new ExtractionMapping("map-1", "prj", "jdoe", "staff",
                "ftm", "doc-1", RowSourceOptions.defaults(), Map.of("member",
                new ExtractionMapping.EntityMapping("Person", List.of("passport"), Map.of("name",
                        new ExtractionMapping.PropertyMapping(List.of("full_name"), null, null, null, null))))));
        List<Statement> statements =
                executor.statements(new Row(1L, Map.of("passport", "AB123", "full_name", "Jane Doe")));

        assertThat(repository.save("prj", "run-1", statements)).isEqualTo(1);
        assertThat(repository.save("prj", "run-2", statements)).isEqualTo(0);
        assertThat(dbRule.dsl().fetchCount(STATEMENT)).isEqualTo(1);
    }
```

Imports to add: `org.icij.datashare.tabular.ExtractionMapping`, `org.icij.datashare.tabular.MappingExecutor`, `org.icij.datashare.tabular.Row`, `org.icij.datashare.tabular.RowSourceOptions`, `java.util.Map`.

This is the test the deterministic entity id exists for: a per-run id would insert a second row here.

- [ ] **Step 2: Run the test and watch it fail**

Run: `mvn -pl datashare-db -am test -Dtest=JooqStatementRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS on both parameters if everything above is right. Prove it is a real test by temporarily seeding the entity id with `java.util.UUID.randomUUID()` in `MappingExecutor.id`, watching the second save return 1 and the count reach 2, then reverting.

- [ ] **Step 3: Run the full suite of every module this branch touched**

Run, in the background, polling:

```bash
mvn -pl datashare-api,datashare-db,datashare-app -am test
```

Expected: the same failures as the base branch and no others. Four classes fail on this box for environment reasons regardless of branch; list them explicitly when reporting.

- [ ] **Step 4: Commit**

`/commit` with: `test(db): prove the executor's statements are written once across two runs`

---

### Task 9: record what the branch deferred

**Files:** none in the repo.

- [ ] **Step 1: Comment on #2206**

Post a comment recording that delete-by-document retraction and the `(prj_id, doc_id)` index it needs were deferred out of #2204 into it: re-running the same mapping is already a no-op, but correcting a mapping and re-running leaves the stale statements behind, and #2206 owns the run.

- [ ] **Step 2: Comment on #2203**

Post a comment recording that the conditional `model_version` update was left undone, since nothing reads the column until the exporter does.

- [ ] **Step 3: Check the boxes on #2204**

Tick the six checkboxes and note that `048` shipped `original_value` as a nullable column, and that the entity id is `SHA-384(model, type, sorted key values)`.

---

## Self-review

**Spec coverage.** Entity id: Task 3. Blank keys: Task 3. Property shapes: Tasks 3 and 4. Dangling reference kept, not cascaded: Task 6, second test. Missing column guard: Task 6. `format` and parse failure: Task 5. `originalValue` outside the hash: Tasks 1 and 2. Changeset 048: Task 2. Constructor and per-entity model checks: Task 6. Sorted alias and property walk: Task 3. Join skipping blanks: Task 4. Acceptance test with a relationship: Task 7. Idempotence: Tasks 7 and 8. Deferrals recorded: Task 9. No spec section is unimplemented.

**Type consistency.** `MappingExecutor(ExtractionMapping)`, `statements(Row)`, `skipped()` are spelled the same in Tasks 3, 6, 7 and 8. `statementsOf` gains its fourth parameter in Task 4 and every later reference passes it. `add` takes a `String` value in Tasks 3 and 4 and a `Value` from Task 5 on, with all four call sites updated in that same task. `Statement.withOriginalValue` is defined in Task 1 and used in Tasks 2 and 5. The test fixtures `mapping`, `entity`, `column`, `literal`, `reference`, `joined`, `formatted` and `row` are declared once each, in the task that first needs them.
