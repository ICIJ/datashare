# Model-neutral core contracts and the FtM model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the small set of interfaces the rest of epic #2194 targets ("a data model" rather than FollowTheMoney directly), the agreed shape of a stored fact, and FtM as the first implementation.

**Architecture:** A neutral package `org.icij.datashare.model` in `datashare-api` holding the atom (`Statement`, `ModelEntity`), the schema vocabulary (`EntityType`, `Property`), the `TargetModel` interface with structural validation shared as a default method, and a registry selecting a model by name. FtM lives in the `org.icij.datashare.model.ftm` subpackage and reads a vendored `defaultModel.json`, because the `ftm.java` library on the classpath is a build-time code generator that supplies no model data at runtime.

**Tech Stack:** Java 21, Jackson (already in `datashare-api`), JUnit 4, fest-assert 1.4. No new Maven dependency in any module.

**Spec:** `docs/superpowers/specs/2026-08-21-target-model-contracts-design.md`

## Global Constraints

- **Java 21** (`pom.xml:85-86`: `maven.compiler.source`/`target` = 21). Records are available.
- **No new Maven dependency, in any module.** `datashare-api` already has jackson-core, jackson-databind, jackson-annotations and `org.icij.datashare.json.JsonObjectMapper`.
- **Ontology neutrality is enforced by package boundary.** No class in `org.icij.datashare.model` may import `org.icij.datashare.model.ftm` or `org.icij.ftm`, with exactly one exception: `TargetModelRegistry`, whose job is to hold the catalogue. Tasks 1 to 3 are committed before any FtM code exists, so the neutral core is proven to compile and test without it.
- **Tests are JUnit 4 with fest-assert 1.4**: `org.junit.Test`, `static org.fest.assertions.Assertions.assertThat`. Test methods are named `test_snake_case_sentence`, matching `datashare-api/src/test/java/org/icij/datashare/text/artifact/ManifestEntryTest.java`.
- **Test command:** `mvn -pl datashare-api -am test -Dtest=<Classes> -Dsurefire.failIfNoSpecifiedTests=false`. Multiple classes are **comma**-separated; a `+` silently matches nothing and reads as green. Never drop `-am`.
- **Model resource:** `ftm/defaultModel-4.10.2.json`, vendored from `https://raw.githubusercontent.com/opensanctions/followthemoney/main/js/src/defaultModel.json`, version `4.10.2`.
- **Statement ids use `Hasher.SHA_384`** (`datashare-api/src/main/java/org/icij/datashare/text/Hasher.java:21`), matching `Entity.DEFAULT_DIGESTER` (`Entity.java:14`). Hex digest length is 96.
- **Comments: default to zero.** Write one only for a non-obvious *why* (a spec quirk, a workaround). Never restate what a line does, never label sections. The comments shown in this plan's code blocks are the only ones intended to ship.
- **Branch:** `feat/2197-target-model`, created before the first commit. Commit with the `/commit` skill where available, otherwise `git commit -m` with the message given. No co-author trailers, no Claude attribution.
- **Closes both issues:** #2197 and #2200.

## Design amendments made while writing this plan

Three changes to the spec, all verified against `defaultModel.json`. Apply these, not the spec, where they differ.

1. **`validate` is a `default` method on `TargetModel`, not per-implementation.** Structural validation is driven entirely by `EntityType`/`Property` data, so every future ontology inherits it rather than reimplementing it. `datashare-api` already uses default methods with logic in 9 interfaces (`function/ThrowingBiConsumer.java`, `reflect/EnumTypeToken.java`). FtM overrides it only to add its own single-`schema` constraint.
2. **For a multi-type entity, a property is checked against the union of its types' properties**, not against each type separately, and `required` is checked per type. Checking each property against every type would reject a legitimately multi-typed entity.
3. **The edge check only fires for ends that `required` does not already cover.** 14 of the 16 edge schemata list both ends in `required`, so an unscoped check would report the same missing property twice. It still earns its place: `Debt` requires `debtor` but not `creditor`, and `ProjectParticipant` requires nothing at all.

4. **Seven simplifications applied after a lazy-code review, already reflected in every code block below.** `Property` carries neither `maxLength` nor `reverse` and `EntityType` carries no `label`, because nothing in this epic reads them and one line each restores them from the bundled file. `TargetModelRegistry` is a literal `Map.of` over one instance rather than a supplier map plus a cache, and has no private constructor. `FtmTargetModel` has one constructor and a private `RESOURCE`. `Statement` inlines its NUL separator. `FtmTargetModel` uses `HashMap`/`HashSet` where `Map.copyOf`/`Set.copyOf` discards insertion order anyway. `version()` is kept on purpose: it is the only thing that fails when the bundled resource is wrong or silently updated, and the only record of which ontology version produced a fact.

Also: `ModelEntity.from` **deduplicates values** while preserving order. Because the statement id includes provenance, the same value seen in two columns is two statements (two pieces of evidence) but one property value.

## File Structure

```
datashare-api/src/main/java/org/icij/datashare/model/
  Statement.java            the atom: one fact, its deterministic id, its cell-level provenance
  ModelEntity.java          the read-side projection: id, types, multi-valued properties
  TargetModel.java          the contract, plus shared structural validation and Violation
  EntityType.java           one schema: flattened properties, required, edge, ancestors
  Property.java             one property declaration
  TargetModelRegistry.java  selects a model by name; the only neutral class knowing FtM exists
  ftm/FtmTargetModel.java   loads the vendored model, serializes and parses FtM JSON
datashare-api/src/main/resources/ftm/defaultModel-4.10.2.json

datashare-api/src/test/java/org/icij/datashare/model/
  StatementTest.java
  ModelEntityTest.java
  TargetModelValidationTest.java        exercises shared validation through a fake, non-FtM ontology
  ftm/FtmTargetModelTest.java
  ftm/FtmTargetModelSerializationTest.java
  TargetModelRegistryTest.java
```

Records nest in the type that owns them (`Statement.Provenance`, `EntityType.Edge`, `TargetModel.Violation`), following the `ManifestRepository.ManifestAction` precedent in this module.

---

### Task 1: The statement atom

**Files:**
- Create: `datashare-api/src/main/java/org/icij/datashare/model/Statement.java`
- Test: `datashare-api/src/test/java/org/icij/datashare/model/StatementTest.java`

**Interfaces:**
- Consumes: `org.icij.datashare.text.Hasher` (existing).
- Produces: `Statement(String id, String model, String entityId, String entityType, String property, String value, Provenance provenance)`; `Statement.of(String model, String entityId, String entityType, String property, String value, Provenance provenance)` returning `Statement` with the id computed; `Statement#qualifiedProperty()` returning `String`; `Statement.Provenance(String documentId, String sheet, long rowNumber, String column)`.

- [ ] **Step 1: Confirm the workspace**

The branch `feat/2197-target-model` already exists and is checked out in the worktree you are working in. Do not create a branch and do not `cd` elsewhere: every path below is relative to the worktree root.

- [ ] **Step 2: Write the failing test**

Create `datashare-api/src/test/java/org/icij/datashare/model/StatementTest.java`:

```java
package org.icij.datashare.model;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class StatementTest {
    private final Statement.Provenance provenance = new Statement.Provenance("doc-1", "Sheet1", 12, "full_name");

    @Test
    public void test_the_same_fact_gets_the_same_id() {
        Statement one = Statement.of("ftm", "person-1", "Person", "name", "Jane Doe", provenance);
        Statement two = Statement.of("ftm", "person-1", "Person", "name", "Jane Doe", provenance);

        assertThat(one.id()).isEqualTo(two.id());
        assertThat(one.id().length()).isEqualTo(96);
    }

    @Test
    public void test_the_id_changes_with_the_value() {
        Statement one = Statement.of("ftm", "person-1", "Person", "name", "Jane Doe", provenance);
        Statement two = Statement.of("ftm", "person-1", "Person", "name", "Jane Doa", provenance);

        assertThat(one.id()).isNotEqualTo(two.id());
    }

    @Test
    public void test_the_id_changes_with_the_cell_it_came_from() {
        Statement one = Statement.of("ftm", "person-1", "Person", "name", "Jane Doe", provenance);
        Statement two = Statement.of("ftm", "person-1", "Person", "name", "Jane Doe",
                new Statement.Provenance("doc-1", "Sheet1", 12, "legal_name"));
        Statement three = Statement.of("ftm", "person-1", "Person", "name", "Jane Doe",
                new Statement.Provenance("doc-1", "Sheet2", 12, "full_name"));

        assertThat(one.id()).isNotEqualTo(two.id());
        assertThat(one.id()).isNotEqualTo(three.id());
    }

    @Test
    public void test_a_single_table_source_has_no_sheet() {
        Statement statement = Statement.of("ftm", "person-1", "Person", "name", "Jane Doe",
                new Statement.Provenance("doc-1", null, 1, "full_name"));

        assertThat(statement.id().length()).isEqualTo(96);
    }

    @Test
    public void test_the_qualified_property_prefixes_the_model() {
        Statement statement = Statement.of("ftm", "person-1", "Person", "birthDate", "1980-04-02", provenance);

        assertThat(statement.qualifiedProperty()).isEqualTo("ftm:birthDate");
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -pl datashare-api -am test -Dtest=StatementTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, compilation error, `package org.icij.datashare.model does not exist` / `cannot find symbol Statement`.

- [ ] **Step 4: Write the minimal implementation**

Create `datashare-api/src/main/java/org/icij/datashare/model/Statement.java`. The separator in `id` is the six-character Java escape `"\u0000"`, not a literal control character:

```java
package org.icij.datashare.model;

import org.icij.datashare.text.Hasher;

public record Statement(String id, String model, String entityId, String entityType,
                        String property, String value, Provenance provenance) {

    public record Provenance(String documentId, String sheet, long rowNumber, String column) { }

    public static Statement of(String model, String entityId, String entityType,
                               String property, String value, Provenance provenance) {
        return new Statement(id(model, entityId, entityType, property, value, provenance),
                model, entityId, entityType, property, value, provenance);
    }

    public String qualifiedProperty() {
        return model + ":" + property;
    }

    // NUL-separated rather than a printable delimiter: a cell value can hold any printable
    // character, so only a value containing a literal NUL could forge a collision.
    private static String id(String model, String entityId, String entityType,
                             String property, String value, Provenance provenance) {
        return Hasher.SHA_384.hash(String.join("\u0000", model, entityId, entityType, property, value,
                provenance.documentId(),
                provenance.sheet() == null ? "" : provenance.sheet(),
                String.valueOf(provenance.rowNumber()),
                provenance.column()));
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl datashare-api -am test -Dtest=StatementTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 5 tests.

- [ ] **Step 6: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/model/Statement.java \
        datashare-api/src/test/java/org/icij/datashare/model/StatementTest.java
git commit -m "feat(model): add the statement atom with cell-level provenance"
```

---

### Task 2: Rebuilding an entity from its statements

**Files:**
- Create: `datashare-api/src/main/java/org/icij/datashare/model/ModelEntity.java`
- Test: `datashare-api/src/test/java/org/icij/datashare/model/ModelEntityTest.java`

**Interfaces:**
- Consumes: `Statement` and `Statement.Provenance` from Task 1.
- Produces: `ModelEntity(String id, Set<String> types, Map<String, List<String>> properties)`; `ModelEntity.from(Collection<Statement>)` returning `ModelEntity`, throwing `IllegalArgumentException` on an empty collection or on statements of more than one entity.

- [ ] **Step 1: Write the failing test**

Create `datashare-api/src/test/java/org/icij/datashare/model/ModelEntityTest.java`:

```java
package org.icij.datashare.model;

import org.junit.Test;

import java.util.List;

import static org.fest.assertions.Assertions.assertThat;

public class ModelEntityTest {
    @Test
    public void test_groups_the_values_of_a_property_in_order() {
        ModelEntity entity = ModelEntity.from(List.of(
                statement("name", "Jane Doe", "full_name"),
                statement("birthDate", "1980-04-02", "dob"),
                statement("name", "J. Doe", "known_as")));

        assertThat(entity.id()).isEqualTo("person-1");
        assertThat(entity.types()).containsOnly("Person");
        assertThat(entity.properties().get("name")).containsExactly("Jane Doe", "J. Doe");
        assertThat(entity.properties().get("birthDate")).containsExactly("1980-04-02");
    }

    @Test
    public void test_unions_the_types_of_the_statements() {
        ModelEntity entity = ModelEntity.from(List.of(
                statement("name", "Jane Doe", "full_name"),
                Statement.of("ftm", "person-1", "LegalEntity", "name", "Jane Doe",
                        new Statement.Provenance("doc-2", null, 3, "counterparty"))));

        assertThat(entity.types()).containsOnly("Person", "LegalEntity");
    }

    @Test
    public void test_the_same_value_from_two_cells_is_two_statements_but_one_value() {
        ModelEntity entity = ModelEntity.from(List.of(
                statement("name", "Jane Doe", "full_name"),
                statement("name", "Jane Doe", "legal_name")));

        assertThat(entity.properties().get("name")).containsExactly("Jane Doe");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_refuses_statements_belonging_to_two_entities() {
        ModelEntity.from(List.of(
                statement("name", "Jane Doe", "full_name"),
                Statement.of("ftm", "person-2", "Person", "name", "John Doe",
                        new Statement.Provenance("doc-1", "Sheet1", 13, "full_name"))));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_refuses_an_empty_collection() {
        ModelEntity.from(List.of());
    }

    private Statement statement(String property, String value, String column) {
        return Statement.of("ftm", "person-1", "Person", property, value,
                new Statement.Provenance("doc-1", "Sheet1", 12, column));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl datashare-api -am test -Dtest=ModelEntityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, compilation error, `cannot find symbol ModelEntity`.

- [ ] **Step 3: Write the minimal implementation**

Create `datashare-api/src/main/java/org/icij/datashare/model/ModelEntity.java`:

```java
package org.icij.datashare.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public record ModelEntity(String id, Set<String> types, Map<String, List<String>> properties) {

    public static ModelEntity from(Collection<Statement> statements) {
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("cannot rebuild an entity from no statement");
        }
        Set<String> ids = new TreeSet<>();
        Set<String> types = new LinkedHashSet<>();
        Map<String, Set<String>> values = new LinkedHashMap<>();
        for (Statement statement : statements) {
            ids.add(statement.entityId());
            types.add(statement.entityType());
            values.computeIfAbsent(statement.property(), property -> new LinkedHashSet<>()).add(statement.value());
        }
        if (ids.size() > 1) {
            throw new IllegalArgumentException("statements belong to " + ids.size() + " entities: " + ids);
        }
        Map<String, List<String>> properties = new LinkedHashMap<>();
        values.forEach((property, distinct) -> properties.put(property, new ArrayList<>(distinct)));
        return new ModelEntity(ids.first(), types, properties);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl datashare-api -am test -Dtest=ModelEntityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/model/ModelEntity.java \
        datashare-api/src/test/java/org/icij/datashare/model/ModelEntityTest.java
git commit -m "feat(model): rebuild a multi-valued entity from its statements"
```

---

### Task 3: The contract and shared structural validation

This is the task that carries the ontology-neutrality claim. Its test defines a **fake, non-FtM ontology** and validates entities through it, so the shared validation is proven to work for a model that is not FollowTheMoney. No FtM code exists in the repository at this commit.

**Files:**
- Create: `datashare-api/src/main/java/org/icij/datashare/model/Property.java`
- Create: `datashare-api/src/main/java/org/icij/datashare/model/EntityType.java`
- Create: `datashare-api/src/main/java/org/icij/datashare/model/TargetModel.java`
- Test: `datashare-api/src/test/java/org/icij/datashare/model/TargetModelValidationTest.java`

**Interfaces:**
- Consumes: `ModelEntity` from Task 2.
- Produces:
  - `Property(String name, String qname, String type, String range, boolean stub)`
  - `EntityType(String name, boolean isAbstract, Set<String> ancestors, Map<String, Property> properties, Set<String> required, EntityType.Edge edge)` where `properties` is already flattened over `ancestors` and `edge` is null for a non-edge type
  - `EntityType.Edge(String source, String target, boolean directed)`
  - `TargetModel` with abstract `String name()`, `String version()`, `Optional<EntityType> type(String)`, `String serialize(ModelEntity)`, `ModelEntity parse(String)`, and default `Optional<Property> property(String type, String name)` and `List<Violation> validate(ModelEntity)`
  - `TargetModel.Violation(String message)`

- [ ] **Step 1: Write the failing test**

Create `datashare-api/src/test/java/org/icij/datashare/model/TargetModelValidationTest.java`:

```java
package org.icij.datashare.model;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.fest.assertions.Assertions.assertThat;

public class TargetModelValidationTest {
    private final TargetModel model = new FakeModel();

    @Test
    public void test_a_valid_entity_has_no_violation() {
        assertThat(model.validate(new ModelEntity("p-1", Set.of("Person"),
                Map.of("name", List.of("Jane Doe"))))).isEmpty();
    }

    @Test
    public void test_a_property_declared_by_an_ancestor_resolves() {
        assertThat(model.property("Person", "name").isPresent()).isTrue();
        assertThat(model.property("Person", "nope").isPresent()).isFalse();
        assertThat(model.property("Nope", "name").isPresent()).isFalse();
    }

    @Test
    public void test_an_unknown_type_is_a_violation() {
        List<TargetModel.Violation> violations = model.validate(
                new ModelEntity("p-1", Set.of("Robot"), Map.of("name", List.of("Jane Doe"))));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("Robot");
        assertThat(violations.get(0).message()).contains("fake");
    }

    @Test
    public void test_an_abstract_type_cannot_be_instantiated() {
        List<TargetModel.Violation> violations = model.validate(
                new ModelEntity("t-1", Set.of("Thing"), Map.of("name", List.of("Jane Doe"))));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("Thing");
        assertThat(violations.get(0).message()).contains("abstract");
    }

    @Test
    public void test_an_undeclared_property_is_a_violation() {
        List<TargetModel.Violation> violations = model.validate(new ModelEntity("p-1", Set.of("Person"),
                Map.of("name", List.of("Jane Doe"), "shoeSize", List.of("42"))));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("shoeSize");
    }

    @Test
    public void test_a_stub_property_cannot_be_written() {
        List<TargetModel.Violation> violations = model.validate(new ModelEntity("p-1", Set.of("Person"),
                Map.of("name", List.of("Jane Doe"), "employers", List.of("e-1"))));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("employers");
        assertThat(violations.get(0).message()).contains("stub");
    }

    @Test
    public void test_a_missing_required_property_is_a_violation() {
        List<TargetModel.Violation> violations = model.validate(
                new ModelEntity("p-1", Set.of("Person"), Map.of()));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("Person");
        assertThat(violations.get(0).message()).contains("name");
    }

    @Test
    public void test_a_blank_required_property_is_a_violation() {
        List<TargetModel.Violation> violations = model.validate(
                new ModelEntity("p-1", Set.of("Person"), Map.of("name", List.of(" "))));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("name");
    }

    @Test
    public void test_an_edge_needs_both_of_its_ends() {
        List<TargetModel.Violation> violations = model.validate(new ModelEntity("e-1", Set.of("Employment"),
                Map.of("employee", List.of("p-1"))));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("Employment");
        assertThat(violations.get(0).message()).contains("employer");
    }

    @Test
    public void test_a_multi_type_entity_may_use_a_property_of_either_type() {
        assertThat(model.validate(new ModelEntity("p-1", Set.of("Person", "Company"),
                Map.of("name", List.of("Jane Doe"), "vatNumber", List.of("FR123"))))).isEmpty();
    }

    private static class FakeModel implements TargetModel {
        private static final Map<String, EntityType> TYPES = Map.of(
                "Thing", new EntityType("Thing", true, Set.of("Thing"),
                        Map.of("name", property("name")), Set.of("name"), null),
                "Person", new EntityType("Person", false, Set.of("Person", "Thing"),
                        Map.of("name", property("name"), "employers", stub("employers")),
                        Set.of("name"), null),
                "Company", new EntityType("Company", false, Set.of("Company", "Thing"),
                        Map.of("name", property("name"), "vatNumber", property("vatNumber")),
                        Set.of("name"), null),
                "Employment", new EntityType("Employment", false, Set.of("Employment"),
                        Map.of("employee", property("employee"), "employer", property("employer")),
                        Set.of("employee"), new EntityType.Edge("employee", "employer", true)));

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public Optional<EntityType> type(String name) {
            return Optional.ofNullable(TYPES.get(name));
        }

        @Override
        public String serialize(ModelEntity entity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelEntity parse(String json) {
            throw new UnsupportedOperationException();
        }

        private static Property property(String name) {
            return new Property(name, "Fake:" + name, "string", null, false);
        }

        private static Property stub(String name) {
            return new Property(name, "Fake:" + name, "entity", "Employment", true);
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl datashare-api -am test -Dtest=TargetModelValidationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, compilation error, `cannot find symbol TargetModel` / `EntityType` / `Property`.

- [ ] **Step 3: Write the two records**

Create `datashare-api/src/main/java/org/icij/datashare/model/Property.java`:

```java
package org.icij.datashare.model;

public record Property(String name, String qname, String type, String range, boolean stub) { }
```

Create `datashare-api/src/main/java/org/icij/datashare/model/EntityType.java`:

```java
package org.icij.datashare.model;

import java.util.Map;
import java.util.Set;

public record EntityType(String name, boolean isAbstract, Set<String> ancestors,
                         Map<String, Property> properties, Set<String> required, Edge edge) {

    public record Edge(String source, String target, boolean directed) { }
}
```

- [ ] **Step 4: Write the contract**

Create `datashare-api/src/main/java/org/icij/datashare/model/TargetModel.java`:

```java
package org.icij.datashare.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public interface TargetModel {
    String name();

    String version();

    Optional<EntityType> type(String name);

    String serialize(ModelEntity entity);

    ModelEntity parse(String json);

    record Violation(String message) { }

    default Optional<Property> property(String type, String name) {
        return type(type).map(found -> found.properties().get(name));
    }

    default List<Violation> validate(ModelEntity entity) {
        List<Violation> violations = new ArrayList<>();
        if (entity.types().isEmpty()) {
            violations.add(new Violation("entity '" + entity.id() + "' has no type"));
        }
        List<EntityType> types = new ArrayList<>();
        for (String name : entity.types()) {
            Optional<EntityType> found = type(name);
            if (found.isEmpty()) {
                violations.add(new Violation("unknown type '" + name + "' in model '" + name() + "'"));
            } else {
                if (found.get().isAbstract()) {
                    violations.add(new Violation("type '" + name + "' is abstract and cannot be instantiated"));
                }
                types.add(found.get());
            }
        }
        Map<String, Property> declared = new LinkedHashMap<>();
        types.forEach(type -> type.properties().forEach(declared::putIfAbsent));
        entity.properties().keySet().forEach(property -> {
            Property declaration = declared.get(property);
            if (declaration == null) {
                violations.add(new Violation("no property '" + property + "' on " + entity.types()));
            } else if (declaration.stub()) {
                violations.add(new Violation("property '" + property + "' is a stub: it is inferred from the '"
                        + declaration.range() + "' relation rather than written"));
            }
        });
        for (EntityType type : types) {
            type.required().stream().filter(required -> isBlank(entity, required)).forEach(required ->
                    violations.add(new Violation("type '" + type.name() + "' requires '" + required + "'")));
            if (type.edge() != null) {
                Stream.of(type.edge().source(), type.edge().target())
                        .filter(end -> !type.required().contains(end))
                        .filter(end -> isBlank(entity, end))
                        .forEach(end -> violations.add(
                                new Violation("edge type '" + type.name() + "' needs '" + end + "'")));
            }
        }
        return violations;
    }

    private static boolean isBlank(ModelEntity entity, String property) {
        return entity.properties().getOrDefault(property, List.of()).stream().allMatch(String::isBlank);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl datashare-api -am test -Dtest=TargetModelValidationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 10 tests.

- [ ] **Step 6: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/model/Property.java \
        datashare-api/src/main/java/org/icij/datashare/model/EntityType.java \
        datashare-api/src/main/java/org/icij/datashare/model/TargetModel.java \
        datashare-api/src/test/java/org/icij/datashare/model/TargetModelValidationTest.java
git commit -m "feat(model): add the target model contract with shared structural validation"
```

---

### Task 4: The FtM model, vendored and loaded

**Files:**
- Create: `datashare-api/src/main/resources/ftm/defaultModel-4.10.2.json` (downloaded, 234KB)
- Create: `datashare-api/src/main/java/org/icij/datashare/model/ftm/FtmTargetModel.java`
- Test: `datashare-api/src/test/java/org/icij/datashare/model/ftm/FtmTargetModelTest.java`

**Interfaces:**
- Consumes: `TargetModel`, `EntityType`, `EntityType.Edge`, `Property` from Task 3; `org.icij.datashare.json.JsonObjectMapper.getMapper()` (existing, `JsonObjectMapper.java:274`).
- Produces: `FtmTargetModel()`, a no-argument constructor loading the bundled resource; implements `name()` returning `"ftm"` and `version()` returning `"4.10.2"`. `serialize`/`parse` are added in Task 5 and throw `UnsupportedOperationException` until then.

- [ ] **Step 1: Vendor the model file**

```bash
mkdir -p datashare-api/src/main/resources/ftm
curl -fsSL -o datashare-api/src/main/resources/ftm/defaultModel-4.10.2.json \
  https://raw.githubusercontent.com/opensanctions/followthemoney/main/js/src/defaultModel.json
python3 -c "import json;m=json.load(open('datashare-api/src/main/resources/ftm/defaultModel-4.10.2.json'));print(m['version'], len(m['schemata']))"
```

Expected: `4.10.2 69`. If the version differs, upstream has moved: rename the file to match, update `RESOURCE` and the `version` assertion, and say so in the commit message rather than silently shipping a different model.

- [ ] **Step 2: Write the failing test**

Create `datashare-api/src/test/java/org/icij/datashare/model/ftm/FtmTargetModelTest.java`:

```java
package org.icij.datashare.model.ftm;

import org.icij.datashare.model.EntityType;
import org.icij.datashare.model.Property;
import org.icij.datashare.model.TargetModel;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class FtmTargetModelTest {
    private final TargetModel model = new FtmTargetModel();

    @Test
    public void test_loads_the_bundled_model() {
        assertThat(model.name()).isEqualTo("ftm");
        assertThat(model.version()).isEqualTo("4.10.2");
    }

    @Test
    public void test_looks_a_type_up_by_name() {
        assertThat(model.type("Person").isPresent()).isTrue();
        assertThat(model.type("Persona").isPresent()).isFalse();
    }

    @Test
    public void test_an_inherited_property_resolves_from_the_type_that_declares_it() {
        Property name = model.property("Person", "name").get();

        assertThat(name.qname()).isEqualTo("Thing:name");
        assertThat(name.type()).isEqualTo("name");
    }

    @Test
    public void test_knows_which_types_are_abstract() {
        assertThat(model.type("Thing").get().isAbstract()).isTrue();
        assertThat(model.type("Interest").get().isAbstract()).isTrue();
        assertThat(model.type("LegalEntity").get().isAbstract()).isFalse();
    }

    @Test
    public void test_an_edge_type_carries_its_ends() {
        EntityType.Edge edge = model.type("Employment").get().edge();

        assertThat(edge.source()).isEqualTo("employee");
        assertThat(edge.target()).isEqualTo("employer");
        assertThat(edge.directed()).isTrue();
        assertThat(model.type("Person").get().edge()).isNull();
    }

    @Test
    public void test_required_is_taken_verbatim_rather_than_inherited() {
        assertThat(model.type("Person").get().required()).containsOnly("name");
        assertThat(model.type("Address").get().required()).isEmpty();
        assertThat(model.type("Employment").get().required()).containsOnly("employee", "employer");
    }

    @Test
    public void test_ancestors_include_the_type_itself() {
        assertThat(model.type("Person").get().ancestors()).containsOnly("Person", "LegalEntity", "Thing");
    }

    @Test
    public void test_a_reverse_relation_is_flagged_as_a_stub() {
        Property employers = model.property("Person", "employers").get();

        assertThat(employers.stub()).isTrue();
        assertThat(employers.range()).isEqualTo("Employment");
        assertThat(model.property("Person", "birthDate").get().stub()).isFalse();
    }

    @Test
    public void test_an_unknown_property_or_type_is_empty() {
        assertThat(model.property("Person", "shoeSize").isPresent()).isFalse();
        assertThat(model.property("Persona", "name").isPresent()).isFalse();
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -pl datashare-api -am test -Dtest=FtmTargetModelTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, compilation error, `cannot find symbol FtmTargetModel`.

- [ ] **Step 4: Write the loader**

Create `datashare-api/src/main/java/org/icij/datashare/model/ftm/FtmTargetModel.java`:

```java
package org.icij.datashare.model.ftm;

import com.fasterxml.jackson.databind.JsonNode;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.model.EntityType;
import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.Property;
import org.icij.datashare.model.TargetModel;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The FollowTheMoney model, read from a bundled copy of its prebuilt ontology. The
 * {@code org.icij:ftm.java} artifact on the classpath is a build-time code generator and carries no
 * model data, so the file is vendored: version 4.10.2, retrieved 2026-08-21 from
 * https://raw.githubusercontent.com/opensanctions/followthemoney/main/js/src/defaultModel.json
 */
public class FtmTargetModel implements TargetModel {
    private static final String RESOURCE = "ftm/defaultModel-4.10.2.json";

    private final String version;
    private final Map<String, EntityType> types;

    public FtmTargetModel() {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("no FtM model bundled at " + RESOURCE);
            }
            JsonNode root = JsonObjectMapper.getMapper().readTree(stream);
            this.version = root.get("version").asText();
            this.types = types(root.get("schemata"));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the FtM model at " + RESOURCE, e);
        }
    }

    @Override
    public String name() {
        return "ftm";
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public Optional<EntityType> type(String name) {
        return Optional.ofNullable(types.get(name));
    }

    @Override
    public String serialize(ModelEntity entity) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ModelEntity parse(String json) {
        throw new UnsupportedOperationException();
    }

    private static Map<String, EntityType> types(JsonNode schemata) {
        Map<String, Map<String, Property>> declared = new HashMap<>();
        schemata.fields().forEachRemaining(schema ->
                declared.put(schema.getKey(), properties(schema.getValue().get("properties"))));
        Map<String, EntityType> types = new HashMap<>();
        schemata.fields().forEachRemaining(schema ->
                types.put(schema.getKey(), type(schema.getKey(), schema.getValue(), declared)));
        return Map.copyOf(types);
    }

    private static EntityType type(String name, JsonNode schema, Map<String, Map<String, Property>> declared) {
        Set<String> ancestors = strings(schema.get("schemata"));
        Map<String, Property> properties = new HashMap<>();
        ancestors.forEach(ancestor -> properties.putAll(declared.getOrDefault(ancestor, Map.of())));
        return new EntityType(name, schema.path("abstract").asBoolean(false), ancestors,
                Map.copyOf(properties), strings(schema.path("required")), edge(schema.path("edge")));
    }

    private static Map<String, Property> properties(JsonNode properties) {
        Map<String, Property> declared = new HashMap<>();
        properties.fields().forEachRemaining(property ->
                declared.put(property.getKey(), property(property.getValue())));
        return declared;
    }

    private static Property property(JsonNode property) {
        return new Property(property.get("name").asText(), property.get("qname").asText(),
                property.get("type").asText(), text(property, "range"),
                property.path("stub").asBoolean(false));
    }

    private static EntityType.Edge edge(JsonNode edge) {
        return edge.isMissingNode() ? null : new EntityType.Edge(edge.get("source").asText(),
                edge.get("target").asText(), edge.get("directed").asBoolean(false));
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(value -> values.add(value.asText()));
        return Set.copyOf(values);
    }

    private static String text(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : null;
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl datashare-api -am test -Dtest=FtmTargetModelTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 9 tests.

- [ ] **Step 6: Commit**

```bash
git add datashare-api/src/main/resources/ftm/defaultModel-4.10.2.json \
        datashare-api/src/main/java/org/icij/datashare/model/ftm/FtmTargetModel.java \
        datashare-api/src/test/java/org/icij/datashare/model/ftm/FtmTargetModelTest.java
git commit -m "feat(model): bundle and load the FollowTheMoney model 4.10.2"
```

---

### Task 5: FtM JSON, in and out

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/model/ftm/FtmTargetModel.java` (replace the two `UnsupportedOperationException` bodies, add `validate`, `mostSpecific` and a nested record)
- Test: `datashare-api/src/test/java/org/icij/datashare/model/ftm/FtmTargetModelSerializationTest.java`

**Interfaces:**
- Consumes: everything from Task 4.
- Produces: `FtmTargetModel#serialize(ModelEntity)` returning the FtM entity shape `{"id":..,"schema":..,"properties":{..}}`, throwing `IllegalArgumentException` when the entity's types have no common schema; `FtmTargetModel#parse(String)` returning a single-type `ModelEntity`, throwing `IllegalArgumentException` on unreadable JSON; `FtmTargetModel#validate(ModelEntity)` overriding the default to add the single-schema constraint.

- [ ] **Step 1: Write the failing test**

Create `datashare-api/src/test/java/org/icij/datashare/model/ftm/FtmTargetModelSerializationTest.java`:

```java
package org.icij.datashare.model.ftm;

import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.TargetModel;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.fail;

public class FtmTargetModelSerializationTest {
    private final TargetModel model = new FtmTargetModel();

    @Test
    public void test_round_trips_a_person() {
        ModelEntity person = new ModelEntity("person-1", Set.of("Person"),
                Map.of("name", List.of("Jane Doe", "J. Doe"), "birthDate", List.of("1980-04-02")));

        assertThat(model.parse(model.serialize(person))).isEqualTo(person);
    }

    @Test
    public void test_serializes_the_ftm_entity_shape() {
        String json = model.serialize(new ModelEntity("person-1", Set.of("Person"),
                Map.of("name", List.of("Jane Doe"))));

        assertThat(json).contains("\"id\":\"person-1\"");
        assertThat(json).contains("\"schema\":\"Person\"");
        assertThat(json).contains("\"name\":[\"Jane Doe\"]");
    }

    @Test
    public void test_collapses_a_multi_type_entity_to_its_most_specific_type() {
        String json = model.serialize(new ModelEntity("person-1", Set.of("Person", "LegalEntity", "Thing"),
                Map.of("name", List.of("Jane Doe"))));

        assertThat(json).contains("\"schema\":\"Person\"");
    }

    @Test
    public void test_types_with_no_common_schema_are_a_violation() {
        ModelEntity confused = new ModelEntity("x-1", Set.of("Person", "Company"),
                Map.of("name", List.of("Jane Doe")));

        List<TargetModel.Violation> violations = model.validate(confused);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("no common schema");
    }

    @Test
    public void test_serializing_types_with_no_common_schema_fails() {
        try {
            model.serialize(new ModelEntity("x-1", Set.of("Person", "Company"),
                    Map.of("name", List.of("Jane Doe"))));
            fail("should have refused to pick a schema");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("Person");
            assertThat(e.getMessage()).contains("Company");
        }
    }

    @Test
    public void test_unreadable_json_fails_with_a_clear_error() {
        try {
            model.parse("{\"id\": ");
            fail("should have refused the truncated json");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("FtM");
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl datashare-api -am test -Dtest=FtmTargetModelSerializationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, 6 tests, four with `UnsupportedOperationException` and the violation test finding 0 violations.

- [ ] **Step 3: Add the imports and the FtM entity shape**

In `FtmTargetModel.java`, add to the imports:

```java
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.ArrayList;
import java.util.List;
```

and add the nested record at the end of the class body:

```java
    record FtmEntity(String id, String schema, Map<String, List<String>> properties) { }
```

- [ ] **Step 4: Replace the two stub methods and override validate**

Replace the `serialize` and `parse` bodies in `FtmTargetModel.java` with:

```java
    @Override
    public String serialize(ModelEntity entity) {
        String schema = mostSpecific(entity.types()).orElseThrow(() -> new IllegalArgumentException(
                "types " + entity.types() + " have no common schema in the FtM model"));
        try {
            return JsonObjectMapper.getMapper()
                    .writeValueAsString(new FtmEntity(entity.id(), schema, entity.properties()));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot write entity '" + entity.id() + "' as FtM JSON", e);
        }
    }

    @Override
    public ModelEntity parse(String json) {
        try {
            FtmEntity read = JsonObjectMapper.getMapper().readValue(json, FtmEntity.class);
            return new ModelEntity(read.id(), Set.of(read.schema()), read.properties());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot read FtM JSON", e);
        }
    }

    @Override
    public List<Violation> validate(ModelEntity entity) {
        List<Violation> violations = new ArrayList<>(TargetModel.super.validate(entity));
        if (entity.types().size() > 1 && mostSpecific(entity.types()).isEmpty()) {
            violations.add(new Violation("types " + entity.types()
                    + " have no common schema, so the entity cannot be written as FtM JSON"));
        }
        return violations;
    }

    private Optional<String> mostSpecific(Set<String> types) {
        return types.stream()
                .filter(candidate -> type(candidate)
                        .map(found -> found.ancestors().containsAll(types))
                        .orElse(false))
                .findFirst();
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl datashare-api -am test -Dtest=FtmTargetModelSerializationTest,FtmTargetModelTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 15 tests.

- [ ] **Step 6: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/model/ftm/FtmTargetModel.java \
        datashare-api/src/test/java/org/icij/datashare/model/ftm/FtmTargetModelSerializationTest.java
git commit -m "feat(model): serialize and parse FtM entity JSON"
```

---

### Task 6: The registry

**Files:**
- Create: `datashare-api/src/main/java/org/icij/datashare/model/TargetModelRegistry.java`
- Test: `datashare-api/src/test/java/org/icij/datashare/model/TargetModelRegistryTest.java`

**Interfaces:**
- Consumes: `TargetModel` (Task 3), `FtmTargetModel` (Tasks 4 and 5).
- Produces: `TargetModelRegistry.get(String name)` returning a memoized `TargetModel`, throwing `IllegalArgumentException` naming both the request and the known models. This is what #2201 calls to resolve a mapping's `model:` field and what #2204 calls to reach the model it validates against.

- [ ] **Step 1: Write the failing test**

Create `datashare-api/src/test/java/org/icij/datashare/model/TargetModelRegistryTest.java`:

```java
package org.icij.datashare.model;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.fail;

public class TargetModelRegistryTest {
    @Test
    public void test_resolves_the_ftm_model() {
        assertThat(TargetModelRegistry.get("ftm").name()).isEqualTo("ftm");
    }

    @Test
    public void test_parses_the_model_once() {
        assertThat(TargetModelRegistry.get("ftm")).isSameAs(TargetModelRegistry.get("ftm"));
    }

    @Test
    public void test_an_unknown_model_names_the_known_ones() {
        try {
            TargetModelRegistry.get("wikidata");
            fail("should have refused an unregistered model");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("wikidata");
            assertThat(e.getMessage()).contains("ftm");
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl datashare-api -am test -Dtest=TargetModelRegistryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, compilation error, `cannot find symbol TargetModelRegistry`.

- [ ] **Step 3: Write the registry**

Create `datashare-api/src/main/java/org/icij/datashare/model/TargetModelRegistry.java`:

```java
package org.icij.datashare.model;

import org.icij.datashare.model.ftm.FtmTargetModel;

import java.util.Map;
import java.util.TreeSet;

public class TargetModelRegistry {
    private static final Map<String, TargetModel> MODELS = Map.of("ftm", new FtmTargetModel());

    public static TargetModel get(String name) {
        TargetModel model = MODELS.get(name);
        if (model == null) {
            throw new IllegalArgumentException("unknown data model '" + name
                    + "', known models: " + new TreeSet<>(MODELS.keySet()));
        }
        return model;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl datashare-api -am test -Dtest=TargetModelRegistryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 3 tests.

- [ ] **Step 5: Run every test of the new package together**

```bash
mvn -pl datashare-api -am test \
  -Dtest=StatementTest,ModelEntityTest,TargetModelValidationTest,TargetModelRegistryTest,FtmTargetModelTest,FtmTargetModelSerializationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS, 38 tests, 0 failures.

- [ ] **Step 6: Verify the neutral package does not know about FtM**

```bash
grep -rn "ftm\|Ftm" datashare-api/src/main/java/org/icij/datashare/model/*.java
```

Expected: hits in `TargetModelRegistry.java` only (its import and the `Map.of` entry). Any hit in `TargetModel.java`, `EntityType.java`, `Property.java`, `Statement.java` or `ModelEntity.java` is a leak of the first implementation into the contract and must be removed.

- [ ] **Step 7: Run the whole module to catch anything the targeted runs hid**

```bash
mvn -pl datashare-api -am test
```

Expected: PASS. Compare any failure against `main` before blaming this branch: four classes in this repository fail for environment reasons on any branch.

- [ ] **Step 8: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/model/TargetModelRegistry.java \
        datashare-api/src/test/java/org/icij/datashare/model/TargetModelRegistryTest.java
git commit -m "feat(model): select a data model by name through a registry

Closes #2197
Closes #2200"
```

---

## Adding a second ontology later

Documentation for the reviewer and for whoever adds Schema.org or Wikidata, not work in this plan. A new ontology is:

1. A class in its own subpackage implementing `TargetModel`: `name()`, `version()`, `type(String)`, `serialize`, `parse`. Structural validation comes free from the default method, and `property(String, String)` comes free from `type`.
2. Its own bundled ontology resource, loaded into `Map<String, EntityType>` with properties already flattened over `ancestors`. Whatever inheritance rules that ontology has are its loader's business; nothing outside it sees them.
3. One entry in `TargetModelRegistry.MODELS`.
4. Nothing else. `Statement`, `ModelEntity`, the statement table (#2202) and the mapping (#2201) all carry the model name, so facts from two ontologies sit in the same store side by side, namespaced (`ftm:owner` next to `wdt:P127`).

`TargetModelValidationTest` is the proof that step 1 is enough: it validates entities of a fake, non-FtM ontology through the shared code path.

## Self-review notes

- **Spec coverage.** #2197's four checkboxes: model interface with type and property lookup, validate and serialize (Tasks 3 and 5); registry selecting by name with a clear error on an unknown one (Task 6); the agreed statement shape, model-scoped, namespaced property, multi-type entity, structured provenance (Tasks 1 and 2); registry unit test (Task 6). #2200's four: bundle `defaultModel.json` and write its loader (Task 4); multi-valued entity (Task 2); the FtM implementation of the interface (Tasks 4 and 5); round-trip a sample Person (Task 5).
- **One deviation from the spec's test list.** The spec asserted "69 types load". Doing that needs a `types()` accessor on the interface that no consumer wants, so the count assertion is dropped: `version()` plus the specific lookups already fail loudly on a truncated or wrong resource, and Task 4 Step 1 checks the count at download time.
- **Task 5 modifies a file Task 4 created.** Deliberate: `serialize`/`parse` are a separate review gate from loading, and splitting them across two classes would be worse.
