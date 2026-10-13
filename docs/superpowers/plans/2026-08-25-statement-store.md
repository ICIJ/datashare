# Statement store and mapping persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the `statement` and `extraction_mapping` tables with their repositories, so extracted facts have an idempotent system of record and the mapping that produced them is persisted.

**Architecture:** Two additive Liquibase tables in one changeset (`047`), so the jOOQ codegen cycle is paid once. Contracts are interfaces in `datashare-api` next to their domain types, with `Jooq*` implementations in `datashare-db`, following the `ManifestRepository` and `JooqApiKeyRepository` precedents. Statements upsert on a deterministic id and are regrouped into entities on read. Storage only: no HTTP surface, no Guice binding, no production caller.

**Tech Stack:** Java 21 records, Liquibase YAML changesets, jOOQ 3.19.10 (codegen against live Postgres), Jackson via `JsonObjectMapper`, JUnit 4 with `@RunWith(Parameterized.class)`, DbSetup.

**Spec:** `docs/superpowers/specs/2026-08-25-statement-store-design.md`

## Global Constraints

- **Branch:** `feat/2202-statement-store`, off `feat/2197-target-model`, with `feat/2198-tabular-row-source` merged in. Never branch off `main`.
- **Changeset number:** `047`. `046-add-tasks-type.yml` is the current tail of `db.changelog.yml`. Its `changeSet` ids are 79 and 80, so `047` uses **81** and **82**. Author: `pirhoo`.
- **Both dialects:** every repository test runs on SQLite *and* Postgres via `@RunWith(Parameterized.class)` over `DbTestRuleProvider`. A test that passes on one is not done.
- **Clock:** timestamps come from `DatashareTime.getInstance().currentTimeMillis()`, never the database's `now()`. Tests freeze it with `DatashareTimeRule`.
- **Ontology version:** `model_version` is written from `TargetModelRegistry.get(model).version()` and is **outside** the statement id hash. No field is added to `Statement`; this branch must not modify `Statement`.
- **`original_value` is NOT in this branch.** It belongs to #2204 in changeset `048`.
- **Mappings are immutable.** `save` refuses an id that already exists.
- **`extraction_mapping.user_id` is nullable.** Null means `User.nullUser()`, i.e. created from the CLI.
- **No wiring.** Do not add a `RepositoryFactory` method and do not add a Guice binding in `CommonMode`. #2206 hand-wires, as `CommonMode.java:536` already does for `JooqTaskRepository`.
- **Scope discipline:** implement exactly these tasks. Anything else goes in a "Suggested follow-ups" note, not in the diff. No `ponytail:` comments in committed code.
- **Comments:** default to zero. Only a non-obvious *why*, or a public API docstring.
- **Test command:** `mvn -pl datashare-db -am test -Dtest=ClassName -Dsurefire.failIfNoSpecifiedTests=false`. Multiple classes are comma-separated; a `+` silently matches nothing and produces a fake green. Never drop `-am`. Run long builds in the background and poll.

## File Structure

**Create:**
- `datashare-db/src/main/resources/liquibase/changelog/changes/047-add-extraction-mapping-and-statement.yml` — both tables and their indexes.
- `datashare-api/src/main/java/org/icij/datashare/model/StatementRepository.java` — the statement persistence contract.
- `datashare-api/src/main/java/org/icij/datashare/tabular/ExtractionMapping.java` — the mapping record, its nested `EntityMapping` and `PropertyMapping`, and `validate()`.
- `datashare-api/src/main/java/org/icij/datashare/tabular/ExtractionMappingRepository.java` — the mapping persistence contract.
- `datashare-api/src/test/java/org/icij/datashare/tabular/ExtractionMappingTest.java` — record validation, no database.
- `datashare-db/src/main/java/org/icij/datashare/db/JooqStatementRepository.java` — upsert and entity rebuild.
- `datashare-db/src/main/java/org/icij/datashare/db/JooqExtractionMappingRepository.java` — JSON-in-text mapping storage.
- `datashare-db/src/test/java/org/icij/datashare/db/JooqStatementRepositoryTest.java`
- `datashare-db/src/test/java/org/icij/datashare/db/JooqExtractionMappingRepositoryTest.java`

**Modify:**
- `datashare-db/src/main/resources/liquibase/changelog/db.changelog.yml` — append the `047` include.
- `datashare-db/src/test/java/org/icij/datashare/db/DbSetupRule.java` — add both tables to `DELETE_ALL`, and add two repository factory methods.

**Do not modify:** `Statement.java`, `ModelEntity.java`, `TargetModel.java`, anything under `org.icij.datashare.tabular` that came from #2198, `CommonMode.java`, `RepositoryFactory.java`.

---

### Task 1: Branch topology

**Files:**
- No source changes. Git state only.

**Interfaces:**
- Consumes: `feat/2197-target-model` (`Statement`, `ModelEntity`, `TargetModel`, `TargetModelRegistry`, `UnknownTargetModel`), `feat/2198-tabular-row-source` (`RowSourceOptions`, `Row`, `RowSource`).
- Produces: a working tree where `org.icij.datashare.model` and `org.icij.datashare.tabular` both exist in `datashare-api`.

- [ ] **Step 1: Confirm the branch point**

```bash
git rev-parse --abbrev-ref HEAD          # expect: feat/2202-statement-store
git merge-base --is-ancestor feat/2197-target-model HEAD && echo "off 2197: ok"
```

- [ ] **Step 2: Merge the tabular reader branch**

```bash
git merge --no-ff feat/2198-tabular-row-source -m "chore: merge the tabular source reader for the mapping's reader options"
```

If this conflicts, stop and report the conflicting paths rather than resolving blind. The two branches touched disjoint packages (`org.icij.datashare.model` vs `org.icij.datashare.tabular`), so a conflict means something unexpected and is worth a human look.

- [ ] **Step 3: Verify both packages are present**

```bash
ls datashare-api/src/main/java/org/icij/datashare/model/Statement.java
ls datashare-api/src/main/java/org/icij/datashare/tabular/RowSourceOptions.java
```

Expected: both paths exist.

- [ ] **Step 4: Verify the merged tree compiles**

Run in the background and poll:

```bash
mvn -pl datashare-api -am -q -DskipTests compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

The merge commit from Step 2 is the commit. Nothing further to add.

---

### Task 2: Changeset 047 and jOOQ codegen

**Files:**
- Create: `datashare-db/src/main/resources/liquibase/changelog/changes/047-add-extraction-mapping-and-statement.yml`
- Modify: `datashare-db/src/main/resources/liquibase/changelog/db.changelog.yml` (append at end of file)
- Modify: `datashare-db/src/test/java/org/icij/datashare/db/DbSetupRule.java:24-27` (the `DELETE_ALL` table list)
- Test: `datashare-db/src/test/java/org/icij/datashare/db/JooqStatementRepositoryTest.java` (created here, one schema smoke test)

**Interfaces:**
- Consumes: nothing.
- Produces: jOOQ generated `org.icij.datashare.db.Tables.STATEMENT` and `org.icij.datashare.db.Tables.EXTRACTION_MAPPING`, with columns `ID, PRJ_ID, RUN_ID, MODEL, MODEL_VERSION, ENTITY_ID, ENTITY_TYPE, PROPERTY, VALUE, DOC_ID, SHEET, ROW_NUMBER, COLUMN_NAME, FIRST_SEEN, LAST_SEEN` and `ID, PRJ_ID, USER_ID, NAME, DEFINITION, CREATED_AT` respectively. Also `DbSetupRule.createStatementRepository()` and `DbSetupRule.createExtractionMappingRepository()` (added in Tasks 4 and 6; only the `DELETE_ALL` change lands here).

- [ ] **Step 1: Write the failing schema test**

Create `datashare-db/src/test/java/org/icij/datashare/db/JooqStatementRepositoryTest.java`:

```java
package org.icij.datashare.db;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collection;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.db.Tables.EXTRACTION_MAPPING;
import static org.icij.datashare.db.Tables.STATEMENT;

@RunWith(Parameterized.class)
public class JooqStatementRepositoryTest {
    @Rule public DbSetupRule dbRule;

    @Test
    public void test_statement_table_exists() {
        assertThat(dbRule.dsl().fetchCount(STATEMENT)).isEqualTo(0);
    }

    @Test
    public void test_extraction_mapping_table_exists() {
        assertThat(dbRule.dsl().fetchCount(EXTRACTION_MAPPING)).isEqualTo(0);
    }

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {DbTestRuleProvider.getSqliteRule()},
                {DbTestRuleProvider.getPostgresRule()}
        });
    }

    public JooqStatementRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
    }
}
```

Add the `dsl()` helper to `DbSetupRule.java`, next to the other factory methods (after `createTaskRepository()`, around line 61):

```java
    org.jooq.DSLContext dsl() {
        return org.jooq.impl.DSL.using(dataSource, RepositoryFactoryImpl.guessSqlDialectFrom(dataSourceUrl));
    }
```

- [ ] **Step 2: Run it to verify it fails**

```bash
mvn -pl datashare-db -am test -Dtest=JooqStatementRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: COMPILATION FAILURE, `cannot find symbol: variable STATEMENT` / `variable EXTRACTION_MAPPING`. The generated classes do not exist yet. This is the red state.

- [ ] **Step 3: Write the changeset**

Create `datashare-db/src/main/resources/liquibase/changelog/changes/047-add-extraction-mapping-and-statement.yml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 81
      author: pirhoo
      changes:
        - createTable:
            tableName: statement
            columns:
              - column:
                  name: id
                  type: varchar(96)
                  constraints:
                    primaryKey: true
              - column:
                  name: prj_id
                  type: varchar(96)
                  constraints:
                    nullable: false
              - column:
                  name: run_id
                  type: varchar(96)
                  constraints:
                    nullable: false
              - column:
                  name: model
                  type: varchar(32)
                  constraints:
                    nullable: false
              - column:
                  name: model_version
                  type: varchar(32)
                  constraints:
                    nullable: false
              - column:
                  name: entity_id
                  type: varchar(96)
                  constraints:
                    nullable: false
              - column:
                  name: entity_type
                  type: varchar(64)
                  constraints:
                    nullable: false
              - column:
                  name: property
                  type: varchar(96)
                  constraints:
                    nullable: false
              - column:
                  name: value
                  type: text
                  constraints:
                    nullable: false
              - column:
                  name: doc_id
                  type: varchar(96)
                  constraints:
                    nullable: false
              - column:
                  name: sheet
                  type: varchar(255)
                  constraints:
                    nullable: false
              - column:
                  name: row_number
                  type: bigint
                  constraints:
                    nullable: false
              - column:
                  name: column_name
                  type: varchar(255)
                  constraints:
                    nullable: false
              - column:
                  name: first_seen
                  type: datetime
                  constraints:
                    nullable: false
              - column:
                  name: last_seen
                  type: datetime
                  constraints:
                    nullable: false
        - createIndex:
            indexName: statement_prj_entity
            tableName: statement
            columns:
              - column:
                  name: prj_id
                  type: varchar(96)
              - column:
                  name: entity_id
                  type: varchar(96)

  - changeSet:
      id: 82
      author: pirhoo
      changes:
        - createTable:
            tableName: extraction_mapping
            columns:
              - column:
                  name: id
                  type: varchar(96)
                  constraints:
                    primaryKey: true
              - column:
                  name: prj_id
                  type: varchar(96)
                  constraints:
                    nullable: false
              - column:
                  name: user_id
                  type: varchar(96)
              - column:
                  name: name
                  type: varchar(255)
                  constraints:
                    nullable: false
              - column:
                  name: definition
                  type: text
                  constraints:
                    nullable: false
              - column:
                  name: created_at
                  type: datetime
                  constraints:
                    nullable: false
        - createIndex:
            indexName: extraction_mapping_prj_id
            tableName: extraction_mapping
            columns:
              - column:
                  name: prj_id
                  type: varchar(96)
```

`user_id` deliberately carries no `constraints` block, exactly as `036-create_task.yml` does, because a mapping can be created from the CLI with no user.

- [ ] **Step 4: Register the changeset**

Append to the end of `datashare-db/src/main/resources/liquibase/changelog/db.changelog.yml`, matching the existing two-line include style:

```yaml
  - include:
      file: changes/047-add-extraction-mapping-and-statement.yml
      relativeToChangelogFile: true
```

- [ ] **Step 5: Add both tables to the test cleanup list**

In `datashare-db/src/test/java/org/icij/datashare/db/DbSetupRule.java`, the `DELETE_ALL` operation names every table explicitly. Add the two new ones, or rows leak between test classes:

```java
    private static final Operation DELETE_ALL = deleteAllFrom(
            "document", "named_entity", "document_user_star", "document_tag", "batch_search_project", "batch_search", "user_inventory",
            "batch_search_query", "batch_search_result", "project", "path_banner", "document_user_recommendation", "api_key",
            "user_history_project", "user_history_project", "user_history", "casbin_rule", "statement", "extraction_mapping");
```

- [ ] **Step 6: Apply the migration to the build database**

```bash
mvn -pl datashare-db liquibase:update
```

Expected: `Liquibase command 'update' was executed successfully`, and the log names changesets 81 and 82.

If this fails with "No JDBC Connection configured", the gitignored `datashare-devenv.properties` is missing from the repository root. `datashare-db/pom.xml:117-135` reads it for `postgresBuildUri`.

- [ ] **Step 7: Regenerate the jOOQ classes**

```bash
mvn -pl datashare-db -am -DskipTests generate-sources
```

jOOQ codegen reads the **live Postgres schema** (`datashare-db/pom.xml:171-184`), which is why Step 6 must come first. Verify:

```bash
grep -c "STATEMENT\|EXTRACTION_MAPPING" datashare-db/target/generated-sources/jooq/org/icij/datashare/db/Tables.java
```

Expected: a non-zero count.

- [ ] **Step 8: Run the test to verify it passes**

```bash
mvn -pl datashare-db -am test -Dtest=JooqStatementRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 4 tests pass (2 tests × 2 dialects).

- [ ] **Step 9: Confirm nothing else broke**

```bash
mvn -pl datashare-db -am test
```

Expected: the existing `datashare-db` suite is green. Per the known-gotchas note, four classes fail for environment reasons on any branch; separate those from anything your change introduced and say which is which.

- [ ] **Step 10: Commit**

```bash
git add datashare-db/src/main/resources/liquibase/changelog/changes/047-add-extraction-mapping-and-statement.yml \
        datashare-db/src/main/resources/liquibase/changelog/db.changelog.yml \
        datashare-db/src/test/java/org/icij/datashare/db/DbSetupRule.java \
        datashare-db/src/test/java/org/icij/datashare/db/JooqStatementRepositoryTest.java
git commit -m "feat(db): add the statement and extraction_mapping tables"
```

---

### Task 3: The `ExtractionMapping` record

**Files:**
- Create: `datashare-api/src/main/java/org/icij/datashare/tabular/ExtractionMapping.java`
- Test: `datashare-api/src/test/java/org/icij/datashare/tabular/ExtractionMappingTest.java`

**Interfaces:**
- Consumes: `RowSourceOptions` (from #2198), `TargetModel`, `TargetModel.Violation`, `TargetModelRegistry`, `UnknownTargetModel` (from #2197).
- Produces:
  - `ExtractionMapping(String id, String projectId, String userId, String name, String model, String documentId, RowSourceOptions options, Map<String, EntityMapping> entities)`
  - `ExtractionMapping.EntityMapping(String type, List<String> keys, Map<String, PropertyMapping> properties)`
  - `ExtractionMapping.PropertyMapping(List<String> columns, String join, String literal, String entity, String format)`
  - `List<TargetModel.Violation> ExtractionMapping.validate()`

- [ ] **Step 1: Write the failing tests**

Create `datashare-api/src/test/java/org/icij/datashare/tabular/ExtractionMappingTest.java`:

```java
package org.icij.datashare.tabular;

import org.icij.datashare.model.UnknownTargetModel;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

public class ExtractionMappingTest {

    private static ExtractionMapping mapping(String model, Map<String, ExtractionMapping.EntityMapping> entities) {
        return new ExtractionMapping("map-1", "prj", "jdoe", "members", model, "doc-1",
                RowSourceOptions.defaults(), entities);
    }

    private static ExtractionMapping.EntityMapping person(Map<String, ExtractionMapping.PropertyMapping> properties) {
        return new ExtractionMapping.EntityMapping("Person", List.of("id"), properties);
    }

    private static ExtractionMapping.PropertyMapping column(String name) {
        return new ExtractionMapping.PropertyMapping(List.of(name), null, null, null, null);
    }

    @Test
    public void test_unknown_model_is_rejected_at_construction() {
        UnknownTargetModel thrown = assertThrows(UnknownTargetModel.class,
                () -> mapping("wikidata", Map.of("member", person(Map.of("name", column("full_name"))))));
        assertThat(thrown.name).isEqualTo("wikidata");
    }

    @Test
    public void test_a_valid_mapping_reports_no_violation() {
        assertThat(mapping("ftm", Map.of("member", person(Map.of("name", column("full_name"))))).validate()).isEmpty();
    }

    @Test
    public void test_validate_reports_an_unknown_entity_type() {
        ExtractionMapping.EntityMapping unknown =
                new ExtractionMapping.EntityMapping("Unicorn", List.of("id"), Map.of("name", column("n")));
        assertThat(mapping("ftm", Map.of("member", unknown)).validate().toString()).contains("Unicorn");
    }

    @Test
    public void test_validate_reports_an_unknown_property_on_a_known_type() {
        assertThat(mapping("ftm", Map.of("member", person(Map.of("hoofSize", column("h"))))).validate().toString())
                .contains("hoofSize");
    }

    @Test
    public void test_property_needs_exactly_one_source() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExtractionMapping.PropertyMapping(List.of(), null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ExtractionMapping.PropertyMapping(List.of("a"), null, "SS", null, null));
    }

    @Test
    public void test_join_needs_more_than_one_column() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExtractionMapping.PropertyMapping(List.of("a"), " ", null, null, null));
    }

    @Test
    public void test_format_needs_columns() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExtractionMapping.PropertyMapping(List.of(), null, "SS", null, "%d.%m.%Y"));
    }

    @Test
    public void test_a_null_user_is_allowed() {
        ExtractionMapping cliAuthored = new ExtractionMapping("map-1", "prj", null, "members", "ftm", "doc-1",
                RowSourceOptions.defaults(), Map.of("member", person(Map.of("name", column("full_name")))));
        assertThat(cliAuthored.userId()).isNull();
    }

    @Test
    public void test_entity_reference_needs_no_column() {
        ExtractionMapping.PropertyMapping reference =
                new ExtractionMapping.PropertyMapping(List.of(), null, null, "member", null);
        assertThat(reference.entity()).isEqualTo("member");
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

```bash
mvn -pl datashare-api -am test -Dtest=ExtractionMappingTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: COMPILATION FAILURE, `cannot find symbol: class ExtractionMapping`.

- [ ] **Step 3: Write the record**

Create `datashare-api/src/main/java/org/icij/datashare/tabular/ExtractionMapping.java`:

```java
package org.icij.datashare.tabular;

import org.icij.datashare.model.TargetModel;
import org.icij.datashare.model.TargetModelRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ExtractionMapping(String id, String projectId, String userId, String name, String model,
                                String documentId, RowSourceOptions options,
                                Map<String, EntityMapping> entities) {

    public ExtractionMapping {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(documentId, "documentId");
        options = options == null ? RowSourceOptions.defaults() : options;
        Objects.requireNonNull(entities, "entities");
        if (entities.isEmpty()) {
            throw new IllegalArgumentException("a mapping builds no entity");
        }
        entities = Map.copyOf(entities);
        TargetModelRegistry.get(Objects.requireNonNull(model, "model"));
    }

    public record EntityMapping(String type, List<String> keys, Map<String, PropertyMapping> properties) {
        public EntityMapping {
            Objects.requireNonNull(type, "type");
            keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("entity type '" + type + "' has no key column");
            }
            properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
        }
    }

    public record PropertyMapping(List<String> columns, String join, String literal, String entity, String format) {
        public PropertyMapping {
            columns = List.copyOf(columns == null ? List.of() : columns);
            long sources = (columns.isEmpty() ? 0 : 1) + (literal == null ? 0 : 1) + (entity == null ? 0 : 1);
            if (sources != 1) {
                throw new IllegalArgumentException(
                        "a property is filled from exactly one of columns, literal or entity, got " + sources);
            }
            if (join != null && columns.size() < 2) {
                throw new IllegalArgumentException("'join' needs more than one column");
            }
            if (format != null && columns.isEmpty()) {
                throw new IllegalArgumentException("'format' applies to columns only");
            }
        }
    }

    /** Checks every entity type and property against the target model. Called on save, not on read,
     *  so a mapping stored before the ontology moved underneath it still loads. */
    public List<TargetModel.Violation> validate() {
        TargetModel target = TargetModelRegistry.get(model);
        List<TargetModel.Violation> violations = new ArrayList<>();
        entities.forEach((alias, entity) -> {
            if (target.type(entity.type()).isEmpty()) {
                violations.add(new TargetModel.Violation(
                        "unknown type '" + entity.type() + "' for entity '" + alias + "' in model '" + model + "'"));
                return;
            }
            entity.properties().keySet().stream()
                    .filter(property -> target.property(entity.type(), property).isEmpty())
                    .forEach(property -> violations.add(new TargetModel.Violation(
                            "no property '" + property + "' on '" + entity.type() + "'")));
        });
        return violations;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
mvn -pl datashare-api -am test -Dtest=ExtractionMappingTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 9 tests pass.

- [ ] **Step 5: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/tabular/ExtractionMapping.java \
        datashare-api/src/test/java/org/icij/datashare/tabular/ExtractionMappingTest.java
git commit -m "feat(tabular): add the extraction mapping and validate it against its target model"
```

---

### Task 4: Statement upsert

**Files:**
- Create: `datashare-api/src/main/java/org/icij/datashare/model/StatementRepository.java`
- Create: `datashare-db/src/main/java/org/icij/datashare/db/JooqStatementRepository.java`
- Modify: `datashare-db/src/test/java/org/icij/datashare/db/DbSetupRule.java` (add `createStatementRepository()`)
- Test: `datashare-db/src/test/java/org/icij/datashare/db/JooqStatementRepositoryTest.java` (extend)

**Interfaces:**
- Consumes: `Statement`, `Statement.Provenance`, `TargetModelRegistry` (Task 1); generated `Tables.STATEMENT` (Task 2).
- Produces:
  - `StatementRepository.save(String projectId, String runId, Collection<Statement> statements) -> int`
  - `StatementRepository.entities(String projectId) -> Stream<ModelEntity>` and `entity(String projectId, String entityId) -> Optional<ModelEntity>` (declared here, implemented in Task 5)
  - `JooqStatementRepository(DataSource, SQLDialect)`
  - `DbSetupRule.createStatementRepository()`

- [ ] **Step 1: Write the failing tests**

Replace the body of `JooqStatementRepositoryTest.java`, keeping the `@Parameterized` scaffolding, with:

```java
package org.icij.datashare.db;

import org.icij.datashare.model.Statement;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.time.DatashareTime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collection;
import java.util.List;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.db.Tables.STATEMENT;

@RunWith(Parameterized.class)
public class JooqStatementRepositoryTest {
    @Rule public DbSetupRule dbRule;
    @Rule public DatashareTimeRule time = new DatashareTimeRule("2020-07-08T12:13:14Z");
    private final JooqStatementRepository repository;

    private static Statement birthDate(String value) {
        return Statement.of("ftm", "entity-1", "Person", "birthDate", value,
                new Statement.Provenance("doc-1", "", 12L, "dob"));
    }

    @Test
    public void test_save_writes_one_row_per_statement() {
        assertThat(repository.save("prj", "run-1", List.of(birthDate("1970-01-01")))).isEqualTo(1);
        assertThat(dbRule.dsl().fetchCount(STATEMENT)).isEqualTo(1);
    }

    @Test
    public void test_save_fills_the_ontology_version_without_the_caller_supplying_it() {
        repository.save("prj", "run-1", List.of(birthDate("1970-01-01")));
        assertThat(dbRule.dsl().select(STATEMENT.MODEL_VERSION).from(STATEMENT).fetchOne().value1())
                .isEqualTo("4.10.2");
    }

    @Test
    public void test_save_stores_the_property_namespaced() {
        repository.save("prj", "run-1", List.of(birthDate("1970-01-01")));
        assertThat(dbRule.dsl().select(STATEMENT.PROPERTY).from(STATEMENT).fetchOne().value1())
                .isEqualTo("ftm:birthDate");
    }

    @Test
    public void test_save_stores_the_provenance() {
        repository.save("prj", "run-1", List.of(birthDate("1970-01-01")));
        var row = dbRule.dsl().selectFrom(STATEMENT).fetchOne();
        assertThat(row.getDocId()).isEqualTo("doc-1");
        assertThat(row.getSheet()).isEqualTo("");
        assertThat(row.getRowNumber()).isEqualTo(12L);
        assertThat(row.getColumnName()).isEqualTo("dob");
        assertThat(row.getRunId()).isEqualTo("run-1");
        assertThat(row.getPrjId()).isEqualTo("prj");
    }

    @Test
    public void test_saving_the_same_data_twice_changes_nothing_except_last_seen() {
        Collection<Statement> statements = List.of(birthDate("1970-01-01"));
        repository.save("prj", "run-1", statements);
        var first = dbRule.dsl().selectFrom(STATEMENT).fetchOne();

        DatashareTime.getInstance().addMilliseconds(60_000);
        repository.save("prj", "run-2", statements);

        assertThat(dbRule.dsl().fetchCount(STATEMENT)).isEqualTo(1);
        var second = dbRule.dsl().selectFrom(STATEMENT).fetchOne();
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getFirstSeen()).isEqualTo(first.getFirstSeen());
        assertThat(second.getLastSeen().isAfter(first.getLastSeen())).isTrue();
    }

    @Test
    public void test_a_different_value_is_a_different_statement() {
        repository.save("prj", "run-1", List.of(birthDate("1970-01-01"), birthDate("1980-02-02")));
        assertThat(dbRule.dsl().fetchCount(STATEMENT)).isEqualTo(2);
    }

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {DbTestRuleProvider.getSqliteRule()},
                {DbTestRuleProvider.getPostgresRule()}
        });
    }

    public JooqStatementRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
        repository = rule.createStatementRepository();
    }
}
```

Add the factory to `DbSetupRule.java`, next to `createTaskRepository()`:

```java
    public JooqStatementRepository createStatementRepository() {
        return new JooqStatementRepository(dataSource, RepositoryFactoryImpl.guessSqlDialectFrom(dataSourceUrl));
    }
```

- [ ] **Step 2: Run them to verify they fail**

```bash
mvn -pl datashare-db -am test -Dtest=JooqStatementRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: COMPILATION FAILURE, `cannot find symbol: class JooqStatementRepository`.

- [ ] **Step 3: Write the contract**

Create `datashare-api/src/main/java/org/icij/datashare/model/StatementRepository.java`:

```java
package org.icij.datashare.model;

import java.util.Collection;
import java.util.Optional;
import java.util.stream.Stream;

/** Persists statements. Implementations own atomicity, as {@code ManifestRepository} does. */
public interface StatementRepository {
    /** Upserts by statement id. An existing row keeps its first_seen and takes the new last_seen.
     *  Returns the number of rows written. */
    int save(String projectId, String runId, Collection<Statement> statements);

    /** Every entity of a project, rebuilt by grouping its statements. Lazily read: the caller closes it. */
    Stream<ModelEntity> entities(String projectId);

    Optional<ModelEntity> entity(String projectId, String entityId);
}
```

- [ ] **Step 4: Write the implementation**

Create `datashare-db/src/main/java/org/icij/datashare/db/JooqStatementRepository.java`:

```java
package org.icij.datashare.db;

import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.Statement;
import org.icij.datashare.model.StatementRepository;
import org.icij.datashare.model.TargetModelRegistry;
import org.icij.datashare.time.DatashareTime;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Stream;

import static org.icij.datashare.db.Tables.STATEMENT;

public class JooqStatementRepository implements StatementRepository {
    private static final int CHUNK = 1_000;
    private final DataSource dataSource;
    private final SQLDialect dialect;

    public JooqStatementRepository(DataSource dataSource, SQLDialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    @Override
    public int save(String projectId, String runId, Collection<Statement> statements) {
        LocalDateTime now = new Timestamp(DatashareTime.getInstance().currentTimeMillis()).toLocalDateTime();
        DSLContext create = DSL.using(dataSource, dialect);
        int written = 0;
        for (Statement statement : statements) {
            written += create.insertInto(STATEMENT)
                    .set(STATEMENT.ID, statement.id())
                    .set(STATEMENT.PRJ_ID, projectId)
                    .set(STATEMENT.RUN_ID, runId)
                    .set(STATEMENT.MODEL, statement.model())
                    .set(STATEMENT.MODEL_VERSION, TargetModelRegistry.get(statement.model()).version())
                    .set(STATEMENT.ENTITY_ID, statement.entityId())
                    .set(STATEMENT.ENTITY_TYPE, statement.entityType())
                    .set(STATEMENT.PROPERTY, statement.qualifiedProperty())
                    .set(STATEMENT.VALUE, statement.value())
                    .set(STATEMENT.DOC_ID, statement.provenance().documentId())
                    .set(STATEMENT.SHEET, statement.provenance().sheet())
                    .set(STATEMENT.ROW_NUMBER, statement.provenance().rowNumber())
                    .set(STATEMENT.COLUMN_NAME, statement.provenance().column())
                    .set(STATEMENT.FIRST_SEEN, now)
                    .set(STATEMENT.LAST_SEEN, now)
                    .onConflict(STATEMENT.ID).doUpdate()
                    .set(STATEMENT.LAST_SEEN, now)
                    .set(STATEMENT.RUN_ID, runId)
                    .execute();
        }
        return written;
    }

    @Override
    public Stream<ModelEntity> entities(String projectId) {
        throw new UnsupportedOperationException("task 5");
    }

    @Override
    public Optional<ModelEntity> entity(String projectId, String entityId) {
        throw new UnsupportedOperationException("task 5");
    }
}
```

`CHUNK` is unused until Step 6, which is not optional: the loop above is the readable version the tests pin down, and Step 6 makes it fast without changing behaviour.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
mvn -pl datashare-db -am test -Dtest=JooqStatementRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 12 tests pass (6 tests × 2 dialects).

- [ ] **Step 6: Batch the insert**

The per-statement loop issues one round trip per row, and one CSV emits 100k+ statements. Replace the loop body with a jOOQ batch, keeping the identical SQL:

```java
    @Override
    public int save(String projectId, String runId, Collection<Statement> statements) {
        LocalDateTime now = new Timestamp(DatashareTime.getInstance().currentTimeMillis()).toLocalDateTime();
        DSLContext create = DSL.using(dataSource, dialect);
        List<Query> queries = statements.stream().map(statement -> (Query) upsert(create, projectId, runId, statement, now)).toList();
        int written = 0;
        for (int from = 0; from < queries.size(); from += CHUNK) {
            written += IntStream.of(create.batch(queries.subList(from, Math.min(from + CHUNK, queries.size()))).execute()).sum();
        }
        return written;
    }
```

with the single-row builder extracted verbatim from Step 4 into a private `upsert(DSLContext, String, String, Statement, LocalDateTime)` returning the `InsertOnDuplicateSetMoreStep<StatementRecord>`. Add imports: `java.util.List`, `java.util.stream.IntStream`, `org.jooq.Query`, `org.icij.datashare.db.tables.records.StatementRecord`, `org.jooq.InsertOnDuplicateSetMoreStep`.

- [ ] **Step 7: Run the tests again to verify batching changed no behaviour**

```bash
mvn -pl datashare-db -am test -Dtest=JooqStatementRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: the same 12 tests pass. If `save` now returns a different count than before, jOOQ's batch is reporting affected rows differently on one dialect; report that rather than loosening the assertion.

- [ ] **Step 8: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/model/StatementRepository.java \
        datashare-db/src/main/java/org/icij/datashare/db/JooqStatementRepository.java \
        datashare-db/src/test/java/org/icij/datashare/db/DbSetupRule.java \
        datashare-db/src/test/java/org/icij/datashare/db/JooqStatementRepositoryTest.java
git commit -m "feat(db): upsert statements idempotently on their deterministic id"
```

---

### Task 5: Entity rebuild

**Files:**
- Modify: `datashare-db/src/main/java/org/icij/datashare/db/JooqStatementRepository.java` (replace the two `UnsupportedOperationException` bodies)
- Test: `datashare-db/src/test/java/org/icij/datashare/db/JooqStatementRepositoryTest.java` (extend)

**Interfaces:**
- Consumes: `ModelEntity.from(Collection<Statement>)` (unions `entityType` across a group, `ModelEntity.java:38`), `Tables.STATEMENT`.
- Produces: working `entities(String projectId)` and `entity(String projectId, String entityId)`.

- [ ] **Step 1: Write the failing tests**

Append to `JooqStatementRepositoryTest.java`, and add the imports `org.icij.datashare.model.ModelEntity`, `java.util.Optional`, `java.util.stream.Stream`:

```java
    private static Statement statement(String entityId, String type, String property, String value) {
        return Statement.of("ftm", entityId, type, property, value,
                new Statement.Provenance("doc-1", "", 12L, property));
    }

    @Test
    public void test_entity_regroups_its_statements() {
        repository.save("prj", "run-1", List.of(
                statement("e-1", "Person", "name", "Ada"),
                statement("e-1", "Person", "birthDate", "1815-12-10")));

        ModelEntity entity = repository.entity("prj", "e-1").orElseThrow();
        assertThat(entity.id()).isEqualTo("e-1");
        assertThat(entity.properties().get("name")).containsExactly("Ada");
        assertThat(entity.properties().get("birthDate")).containsExactly("1815-12-10");
    }

    @Test
    public void test_an_entity_keeps_every_one_of_its_types() {
        repository.save("prj", "run-1", List.of(
                statement("e-1", "Person", "name", "Ada"),
                statement("e-1", "LegalEntity", "name", "Ada")));

        assertThat(repository.entity("prj", "e-1").orElseThrow().types()).contains("Person", "LegalEntity");
    }

    @Test
    public void test_a_property_keeps_every_one_of_its_values() {
        repository.save("prj", "run-1", List.of(
                statement("e-1", "Person", "name", "Ada"),
                statement("e-1", "Person", "name", "Ada Lovelace")));

        assertThat(repository.entity("prj", "e-1").orElseThrow().properties().get("name"))
                .containsOnly("Ada", "Ada Lovelace");
    }

    @Test
    public void test_entity_is_empty_when_the_project_holds_no_statement_for_it() {
        repository.save("prj", "run-1", List.of(statement("e-1", "Person", "name", "Ada")));
        assertThat(repository.entity("other", "e-1")).isEqualTo(Optional.empty());
        assertThat(repository.entity("prj", "e-2")).isEqualTo(Optional.empty());
    }

    @Test
    public void test_entities_returns_every_entity_of_the_project_and_no_other() {
        repository.save("prj", "run-1", List.of(
                statement("e-1", "Person", "name", "Ada"),
                statement("e-2", "Person", "name", "Grace")));
        repository.save("other", "run-2", List.of(statement("e-3", "Person", "name", "Alan")));

        try (Stream<ModelEntity> entities = repository.entities("prj")) {
            assertThat(entities.map(ModelEntity::id).toList()).containsOnly("e-1", "e-2");
        }
    }

    @Test
    public void test_entities_does_not_split_an_entity_across_two_results() {
        repository.save("prj", "run-1", List.of(
                statement("e-1", "Person", "name", "Ada"),
                statement("e-2", "Person", "name", "Grace"),
                statement("e-1", "Person", "birthDate", "1815-12-10")));

        try (Stream<ModelEntity> entities = repository.entities("prj")) {
            List<ModelEntity> found = entities.toList();
            assertThat(found.size()).isEqualTo(2);
            assertThat(found.stream().filter(e -> e.id().equals("e-1")).findFirst().orElseThrow()
                    .properties().keySet()).containsOnly("name", "birthDate");
        }
    }
```

- [ ] **Step 2: Run them to verify they fail**

```bash
mvn -pl datashare-db -am test -Dtest=JooqStatementRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL with `UnsupportedOperationException: task 5` on the six new tests.

- [ ] **Step 3: Implement the rebuild**

In `JooqStatementRepository.java`, replace both stub methods and add the row-to-statement conversion plus the grouping iterator:

```java
    @Override
    public Optional<ModelEntity> entity(String projectId, String entityId) {
        List<Statement> statements = DSL.using(dataSource, dialect)
                .selectFrom(STATEMENT)
                .where(STATEMENT.PRJ_ID.eq(projectId)).and(STATEMENT.ENTITY_ID.eq(entityId))
                .fetch().map(JooqStatementRepository::toStatement);
        return statements.isEmpty() ? Optional.empty() : Optional.of(ModelEntity.from(statements));
    }

    @Override
    public Stream<ModelEntity> entities(String projectId) {
        Stream<Statement> rows = DSL.using(dataSource, dialect)
                .selectFrom(STATEMENT)
                .where(STATEMENT.PRJ_ID.eq(projectId))
                .orderBy(STATEMENT.ENTITY_ID)
                .fetchLazy().stream()
                .map(JooqStatementRepository::toStatement);
        return group(rows);
    }

    private static Statement toStatement(StatementRecord row) {
        return new Statement(row.getId(), row.getModel(), row.getEntityId(), row.getEntityType(),
                row.getProperty().substring(row.getModel().length() + 1), row.getValue(),
                new Statement.Provenance(row.getDocId(), row.getSheet(), row.getRowNumber(), row.getColumnName()));
    }

    // The query is ordered by entity_id, so an entity's statements are consecutive and each group can
    // be emitted without holding the whole project in memory: one CSV can produce 100k+ entities.
    private static Stream<ModelEntity> group(Stream<Statement> statements) {
        Iterator<Statement> rows = statements.iterator();
        Iterator<ModelEntity> entities = new Iterator<>() {
            private Statement pending = rows.hasNext() ? rows.next() : null;

            @Override
            public boolean hasNext() {
                return pending != null;
            }

            @Override
            public ModelEntity next() {
                if (pending == null) {
                    throw new NoSuchElementException();
                }
                List<Statement> group = new ArrayList<>(List.of(pending));
                String entityId = pending.entityId();
                pending = null;
                while (rows.hasNext()) {
                    Statement row = rows.next();
                    if (!row.entityId().equals(entityId)) {
                        pending = row;
                        break;
                    }
                    group.add(row);
                }
                return ModelEntity.from(group);
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(entities, Spliterator.ORDERED), false)
                .onClose(statements::close);
    }
```

Add imports: `java.util.ArrayList`, `java.util.Iterator`, `java.util.List`, `java.util.NoSuchElementException`, `java.util.Spliterator`, `java.util.Spliterators`, `java.util.stream.StreamSupport`, `org.icij.datashare.db.tables.records.StatementRecord`.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
mvn -pl datashare-db -am test -Dtest=JooqStatementRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 24 tests pass (12 tests × 2 dialects).

- [ ] **Step 5: Commit**

```bash
git add datashare-db/src/main/java/org/icij/datashare/db/JooqStatementRepository.java \
        datashare-db/src/test/java/org/icij/datashare/db/JooqStatementRepositoryTest.java
git commit -m "feat(db): rebuild whole entities by grouping their statements"
```

---

### Task 6: Mapping persistence

**Files:**
- Create: `datashare-api/src/main/java/org/icij/datashare/tabular/ExtractionMappingRepository.java`
- Create: `datashare-db/src/main/java/org/icij/datashare/db/JooqExtractionMappingRepository.java`
- Modify: `datashare-db/src/test/java/org/icij/datashare/db/DbSetupRule.java` (add `createExtractionMappingRepository()`)
- Test: `datashare-db/src/test/java/org/icij/datashare/db/JooqExtractionMappingRepositoryTest.java`

**Interfaces:**
- Consumes: `ExtractionMapping` and `ExtractionMapping.validate()` (Task 3); generated `Tables.EXTRACTION_MAPPING` (Task 2); `JsonObjectMapper.writeValueAsStringTyped` / `readValueTyped` (`JooqTaskRepository.java:251` and `:168` are the precedent).
- Produces:
  - `ExtractionMappingRepository.save(ExtractionMapping) -> boolean`, `get(String id) -> Optional<ExtractionMapping>`, `list(String projectId) -> List<ExtractionMapping>`, `delete(String id) -> boolean`
  - `InvalidExtractionMapping extends IllegalArgumentException` with a public `violations` field
  - `DbSetupRule.createExtractionMappingRepository()`

- [ ] **Step 1: Write the failing tests**

Create `datashare-db/src/test/java/org/icij/datashare/db/JooqExtractionMappingRepositoryTest.java`:

```java
package org.icij.datashare.db;

import org.icij.datashare.tabular.ExtractionMapping;
import org.icij.datashare.tabular.InvalidExtractionMapping;
import org.icij.datashare.tabular.RowSourceOptions;
import org.icij.datashare.test.DatashareTimeRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

@RunWith(Parameterized.class)
public class JooqExtractionMappingRepositoryTest {
    @Rule public DbSetupRule dbRule;
    @Rule public DatashareTimeRule time = new DatashareTimeRule("2020-07-08T12:13:14Z");
    private final JooqExtractionMappingRepository repository;

    private static ExtractionMapping mapping(String id, String projectId, String userId, String type) {
        return new ExtractionMapping(id, projectId, userId, "members", "ftm", "doc-1",
                RowSourceOptions.defaults().withDelimiter(';').withCharset(StandardCharsets.UTF_8),
                Map.of("member", new ExtractionMapping.EntityMapping(type, List.of("id"),
                        Map.of("name", new ExtractionMapping.PropertyMapping(List.of("full_name"), null, null, null, null)))));
    }

    @Test
    public void test_save_and_get_round_trips_the_mapping() {
        ExtractionMapping expected = mapping("map-1", "prj", "jdoe", "Person");
        assertThat(repository.save(expected)).isTrue();
        assertThat(repository.get("map-1").orElseThrow()).isEqualTo(expected);
    }

    @Test
    public void test_the_reader_options_survive_the_round_trip() {
        repository.save(mapping("map-1", "prj", "jdoe", "Person"));
        RowSourceOptions options = repository.get("map-1").orElseThrow().options();
        assertThat(options.delimiter()).isEqualTo(';');
        assertThat(options.charset()).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    public void test_a_cli_authored_mapping_has_no_user() {
        repository.save(mapping("map-1", "prj", null, "Person"));
        assertThat(repository.get("map-1").orElseThrow().userId()).isNull();
    }

    @Test
    public void test_a_mapping_is_immutable_once_saved() {
        repository.save(mapping("map-1", "prj", "jdoe", "Person"));
        assertThat(repository.save(mapping("map-1", "prj", "someone-else", "LegalEntity"))).isFalse();
        assertThat(repository.get("map-1").orElseThrow().userId()).isEqualTo("jdoe");
    }

    @Test
    public void test_an_invalid_mapping_is_refused_rather_than_stored() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> repository.save(mapping("map-1", "prj", "jdoe", "Unicorn")));
        assertThat(thrown.violations.toString()).contains("Unicorn");
        assertThat(repository.get("map-1").isPresent()).isFalse();
    }

    @Test
    public void test_list_is_project_scoped() {
        repository.save(mapping("map-1", "prj", "jdoe", "Person"));
        repository.save(mapping("map-2", "other", "jdoe", "Person"));
        assertThat(repository.list("prj").stream().map(ExtractionMapping::id).toList()).containsOnly("map-1");
    }

    @Test
    public void test_get_is_empty_for_an_unknown_id() {
        assertThat(repository.get("nope").isPresent()).isFalse();
    }

    @Test
    public void test_delete_removes_the_mapping() {
        repository.save(mapping("map-1", "prj", "jdoe", "Person"));
        assertThat(repository.delete("map-1")).isTrue();
        assertThat(repository.get("map-1").isPresent()).isFalse();
        assertThat(repository.delete("map-1")).isFalse();
    }

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {DbTestRuleProvider.getSqliteRule()},
                {DbTestRuleProvider.getPostgresRule()}
        });
    }

    public JooqExtractionMappingRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
        repository = rule.createExtractionMappingRepository();
    }
}
```

Add the factory to `DbSetupRule.java`, next to `createStatementRepository()`:

```java
    public JooqExtractionMappingRepository createExtractionMappingRepository() {
        return new JooqExtractionMappingRepository(dataSource, RepositoryFactoryImpl.guessSqlDialectFrom(dataSourceUrl));
    }
```

- [ ] **Step 2: Run them to verify they fail**

```bash
mvn -pl datashare-db -am test -Dtest=JooqExtractionMappingRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: COMPILATION FAILURE, `cannot find symbol: class JooqExtractionMappingRepository`.

- [ ] **Step 3: Write the contract and its error**

Create `datashare-api/src/main/java/org/icij/datashare/tabular/ExtractionMappingRepository.java`:

```java
package org.icij.datashare.tabular;

import java.util.List;
import java.util.Optional;

/** Persists extraction mappings. Implementations own atomicity, as {@code ManifestRepository} does. */
public interface ExtractionMappingRepository {
    /** Saves a new mapping. Returns false when the id already exists: a mapping is immutable, so a
     *  change is a new mapping with a new id, and a run's statements always resolve back to the
     *  mapping that produced them.
     *  @throws InvalidExtractionMapping when the mapping does not validate against its target model. */
    boolean save(ExtractionMapping mapping);

    Optional<ExtractionMapping> get(String id);

    List<ExtractionMapping> list(String projectId);

    boolean delete(String id);
}
```

Create `datashare-api/src/main/java/org/icij/datashare/tabular/InvalidExtractionMapping.java`:

```java
package org.icij.datashare.tabular;

import org.icij.datashare.model.TargetModel;

import java.util.List;

public class InvalidExtractionMapping extends IllegalArgumentException {
    public final List<TargetModel.Violation> violations;

    public InvalidExtractionMapping(String id, List<TargetModel.Violation> violations) {
        super("mapping '%s' does not validate: %s".formatted(id,
                violations.stream().map(TargetModel.Violation::message).toList()));
        this.violations = List.copyOf(violations);
    }
}
```

- [ ] **Step 4: Write the implementation**

Create `datashare-db/src/main/java/org/icij/datashare/db/JooqExtractionMappingRepository.java`:

```java
package org.icij.datashare.db;

import com.fasterxml.jackson.core.type.TypeReference;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.model.TargetModel;
import org.icij.datashare.tabular.ExtractionMapping;
import org.icij.datashare.tabular.ExtractionMappingRepository;
import org.icij.datashare.tabular.InvalidExtractionMapping;
import org.icij.datashare.time.DatashareTime;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import static org.icij.datashare.db.Tables.EXTRACTION_MAPPING;

public class JooqExtractionMappingRepository implements ExtractionMappingRepository {
    private final DataSource dataSource;
    private final SQLDialect dialect;

    public JooqExtractionMappingRepository(DataSource dataSource, SQLDialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    @Override
    public boolean save(ExtractionMapping mapping) {
        List<TargetModel.Violation> violations = mapping.validate();
        if (!violations.isEmpty()) {
            throw new InvalidExtractionMapping(mapping.id(), violations);
        }
        return DSL.using(dataSource, dialect).insertInto(EXTRACTION_MAPPING)
                .set(EXTRACTION_MAPPING.ID, mapping.id())
                .set(EXTRACTION_MAPPING.PRJ_ID, mapping.projectId())
                .set(EXTRACTION_MAPPING.USER_ID, mapping.userId())
                .set(EXTRACTION_MAPPING.NAME, mapping.name())
                .set(EXTRACTION_MAPPING.DEFINITION, JsonObjectMapper.writeValueAsStringTyped(mapping))
                .set(EXTRACTION_MAPPING.CREATED_AT,
                        new Timestamp(DatashareTime.getInstance().currentTimeMillis()).toLocalDateTime())
                .onConflictDoNothing().execute() > 0;
    }

    @Override
    public Optional<ExtractionMapping> get(String id) {
        return Optional.ofNullable(create().select(EXTRACTION_MAPPING.DEFINITION).from(EXTRACTION_MAPPING)
                .where(EXTRACTION_MAPPING.ID.eq(id)).fetchOne()).map(row -> read(row.value1()));
    }

    @Override
    public List<ExtractionMapping> list(String projectId) {
        return create().select(EXTRACTION_MAPPING.DEFINITION).from(EXTRACTION_MAPPING)
                .where(EXTRACTION_MAPPING.PRJ_ID.eq(projectId))
                .orderBy(EXTRACTION_MAPPING.CREATED_AT)
                .fetch().map(row -> read(row.value1()));
    }

    @Override
    public boolean delete(String id) {
        return create().deleteFrom(EXTRACTION_MAPPING).where(EXTRACTION_MAPPING.ID.eq(id)).execute() > 0;
    }

    private DSLContext create() {
        return DSL.using(dataSource, dialect);
    }

    private static ExtractionMapping read(String definition) {
        return JsonObjectMapper.readValueTyped(definition, new TypeReference<>() {});
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
mvn -pl datashare-db -am test -Dtest=JooqExtractionMappingRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 16 tests pass (8 tests × 2 dialects).

If the round-trip test fails on `RowSourceOptions.charset()`, Jackson needs a `Charset` binding. Prefer storing the charset name over adding a global Jackson module: change `RowSourceOptions` only if #2198's record already handles it, otherwise report it rather than editing a file this branch is told not to touch.

- [ ] **Step 6: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/tabular/ExtractionMappingRepository.java \
        datashare-api/src/main/java/org/icij/datashare/tabular/InvalidExtractionMapping.java \
        datashare-db/src/main/java/org/icij/datashare/db/JooqExtractionMappingRepository.java \
        datashare-db/src/test/java/org/icij/datashare/db/DbSetupRule.java \
        datashare-db/src/test/java/org/icij/datashare/db/JooqExtractionMappingRepositoryTest.java
git commit -m "feat(db): persist an immutable extraction mapping as JSON"
```

---

### Task 7: Full verification

**Files:**
- No source changes expected. If a fix is needed, it belongs to the task that introduced the failure.

- [ ] **Step 1: Run the full affected suites**

Run in the background and poll:

```bash
mvn -pl datashare-db -am test
```

- [ ] **Step 2: Separate pre-existing failures from new ones**

Four `datashare-db` classes fail for environment reasons on any branch, so a red suite is not automatically your fault. Do not assume it, prove it.

List the failing classes:

```bash
grep -rl "<<< \(FAILURE\|ERROR\)" datashare-db/target/surefire-reports/*.txt | xargs -n1 basename
```

Any failing class that names `statement`, `extraction_mapping`, `JooqStatementRepository`, `JooqExtractionMappingRepository` or `ExtractionMapping` is yours. For each of the rest, prove it pre-existed by running that one class on the base branch in a throwaway worktree:

```bash
git worktree add /tmp/base-check feat/2197-target-model
cp datashare-devenv.properties /tmp/base-check/
(cd /tmp/base-check && mvn -pl datashare-db -am test -Dtest=<ClassName> -Dsurefire.failIfNoSpecifiedTests=false)
git worktree remove --force /tmp/base-check
```

The `cp` is required: `datashare-devenv.properties` is gitignored, and without it jOOQ codegen fails with "No JDBC Connection configured" rather than running the test.

State explicitly which failures are pre-existing and which, if any, your change introduced. Do not report green while any new failure stands.

- [ ] **Step 3: Review the diff as a hostile reviewer**

```bash
git diff feat/2197-target-model...HEAD --stat
git diff feat/2197-target-model...HEAD
```

For each hunk, name what input it now handles differently. Confirm specifically that:
- `Statement.java`, `ModelEntity.java` and `TargetModel.java` are untouched.
- No `RepositoryFactory` method and no `CommonMode` binding was added.
- No `original_value` column exists anywhere.
- No `ponytail:` comment survives.

- [ ] **Step 4: Report**

Summarise: tasks completed, test counts per dialect, pre-existing failures named separately, and any suggested follow-ups kept out of the diff.

---

## Suggested follow-ups (do NOT implement here)

Recorded on the issues already; listed so an executor does not mistake them for gaps:

- `original_value` column and `Statement` field, in #2204 (changeset `048`).
- `run_id` index on `statement`, in #2203.
- Project deletion dropping `statement` and `extraction_mapping` rows, in #2358.
- Gating `ExtractionMappingRepository` on a `User`, in #2207.
- `model_version` on the indexed entity, in #2205.
