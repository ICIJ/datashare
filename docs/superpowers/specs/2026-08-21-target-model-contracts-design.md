# Design: model-neutral core contracts and the FtM model in-JVM

Issues [#2197](https://github.com/ICIJ/datashare/issues/2197) (model-neutral core contracts) and
[#2200](https://github.com/ICIJ/datashare/issues/2200) (FtM model in-JVM), part of epic
[#2194](https://github.com/ICIJ/datashare/issues/2194) (import structured entities from tabular data
using a data schema). Designed 2026-08-21 against `main` at `446aa50a3`.

The two issues ship as one branch, as the epic's [2026-08-19 state
check](https://github.com/ICIJ/datashare/issues/2194#issuecomment-5346473298) concluded: the result
is an interface plus its only implementation, and splitting them would put the interface in one
review with nothing exercising it.

Startable now, with nothing of the epic landed. #2311 (#2199) touches only `IndexAccessVerifier`,
`IndexResource` and `ProjectResource`, whose deferred items belong to #2205. #2198 is open as PR
[#2353](https://github.com/ICIJ/datashare/pull/2353) and adds only `org.icij.datashare.tabular`.
Zero file overlap with either, and the two tracks meet at #2204, which consumes rows and the model
together.

Consumers, none of which exist yet: #2201 (mapping persistence) resolves a mapping's `model: ftm`
through the registry, #2202 (statement store) persists the atom this design agrees, #2204 (mapping
executor) looks up types and properties and constructs statements, #2203 (exporter) serializes
entities rebuilt from statements, #2205 projects them into `<project>.entities`.

## Goal

One small set of interfaces so the rest of the epic targets "a data model" rather than
FollowTheMoney directly, plus FtM as the sole implementation, plus the agreed shape of a stored
fact. Adding Schema.org or Wikidata later means a new class and a registry entry, not a core rework.

## Current state, verified

- **The FtM Java library supplies nothing at runtime.** `ftm.java-0.3.1.jar` holds 90 entries whose
  only non-class resources are `logback.xml` and its own pom: no `defaultModel.json`, no schema
  YAMLs. `Utils.downloadYamlModels`, `Utils.getYamlContent` and `Utils.findParents` are
  package-private (confirmed with `javap`) and expect a YAML directory the artifact does not carry.
  `0.3.1` (2024-11-04) is the last release on Maven Central.
- **`org.icij.ftm.Model` is codegen-shaped.** Its constructor throws on a map of more than one entry
  and `name()` returns the sole key, so it models one schema per instance and needs a caller-built
  `parents` map. Its distinctive half is generator vocabulary (`attributes()`,
  `parentsAttributes()`, `concreteParent()`, `getImplementsList()`, `isConcrete()`, and a `type()`
  that maps an FtM type to a Java type). What remains for runtime use is `label()`, `isAbstract()`,
  `getExtends()`, `properties()`, `property(n)` and `required()`: six getters over a map we would
  have parsed anyway.
- **The generated types cannot hold an entity.** `org.icij.ftm.Person.getName()` returns `String`,
  not a list. FtM properties are always arrays.
- **`ftm.java` is on one module's classpath.** Declared at `datashare-app/pom.xml:63`, version
  pinned at `pom.xml:91`. `datashare-db`, which will hold #2201's and #2202's repositories, sees
  `datashare-api` and not `datashare-app`.
- **`datashare-api` already has what the loader needs.** Jackson core, databind, annotations and
  `org.icij.datashare.json.JsonObjectMapper`. No new dependency lands anywhere for this work.
- **The only FtM code on `main`** is `org.icij.datashare.ftm.FtmDocument` and the `FtmResource`
  endpoint, both in `datashare-app`. Neither is touched.

### Facts about `defaultModel.json` v4.10.2, checked against the file

234KB, 69 schemata, 20 property types, plus a `version` string.

- Each schema's `schemata` list is exactly the transitive closure of its `extends`, for all 69. So
  resolving inherited properties is a union over that list rather than a walk, and there are **zero**
  conflicting property definitions across any schema's ancestors, so the union is unambiguous.
- `required` is **not** inherited. 28 schemata declare a `required` list smaller than the union over
  their ancestors: `Address` omits the `name` that `Thing` requires. It is therefore taken verbatim
  per schema. A required entry can still name an inherited property (`Person.required` and
  `Company.required` both hold `name`, which only `Thing` and `Mention` declare, `qname: Thing:name`),
  so checking it needs the flattened property map.
- `abstract` appears on 5 schemata only, always `true`: `Analyzable`, `Interest`, `Interval`, `Thing`
  and `Value`. Absent means concrete, so `LegalEntity` and `Organization` are instantiable.
- 90 properties across the model are `stub: true`, every one of them entity-typed. A stub is the
  reverse side of a relation, populated by inference from the edge entity rather than written:
  `Person.employers` is `range: Employment, reverse: employee, stub: true`, and the fact belongs on
  the `Employment` edge.
- `maxLength` is present on every property in the file, so the loader needs no default for it.
- `edge` appears on 16 schemata, carrying `source`, `target`, `directed` and a `label`. This is what
  #2204's relationships hang off: `Employment.edge` is `employee -> employer`, directed.
- A property carries `name`, `qname` (`Person:birthDate`), `label`, `type` (a name into the `types`
  section), `maxLength`, and for entity-typed properties a `range` (the target schema) and a
  `reverse`.

## Design

Package `org.icij.datashare.model` in `datashare-api`, with the implementation in
`org.icij.datashare.model.ftm`.

### The contract

```java
public interface TargetModel {
    String name();                                          // "ftm"
    String version();                                       // "4.10.2"
    Optional<EntityType> type(String name);                 // "Person"
    Optional<Property> property(String type, String name);  // ("Person", "birthDate")
    List<Violation> validate(ModelEntity entity);
    String serialize(ModelEntity entity);
    ModelEntity parse(String json);

    record Violation(String message) { }
}

public record EntityType(String name, String label, boolean isAbstract, Set<String> ancestors,
                         Map<String, Property> properties, Set<String> required, Edge edge) {
    public record Edge(String source, String target, boolean directed) { }
}

public record Property(String name, String qname, String type, int maxLength,
                       String range, String reverse, boolean stub) { }
```

`properties` is the flattened map, inherited properties included, so `property("Person", "name")` is
a lookup and not a traversal. `ancestors` is the schema's own `schemata` list, kept because
`serialize` needs it to pick a most-specific type.

`serialize` and `parse` are on the model rather than deferred to #2203 because they are the model's
own canonical single-entity representation, which #2200's round-trip checkbox requires. #2203 owns a
different seam: a run to a stream of output, in a chosen format, read from the statement store. It
will call `serialize` per entity.

### The registry

```java
public class TargetModelRegistry {
    public static TargetModel get(String name);
}
```

A `Map.of("ftm", ...)` over one memoized instance: the parsed model is immutable, and the 234KB
parse should happen once per JVM rather than once per call. An unknown name throws
`IllegalArgumentException` naming both the requested model and the known ones, which is what
#2201's "an unknown target schema fails with a clear error rather than silently defaulting to FtM"
asserts against. Not `ServiceLoader` and not Guice multibinding: `datashare-db` would need the
injector to resolve a mapping's model, and discovery buys nothing until a model ships from outside
this repo.

### The atom

```java
public record Statement(String id, String model, String entityId, String entityType,
                        String property, String value, Provenance provenance) {
    public static Statement of(String model, String entityId, String entityType,
                               String property, String value, Provenance provenance);
    public String qualifiedProperty();   // "ftm:birthDate"

    public record Provenance(String documentId, String sheet, long rowNumber, String column) { }
}

public record ModelEntity(String id, Set<String> types, Map<String, List<String>> properties) {
    public static ModelEntity from(Collection<Statement> statements);
}
```

Four decisions in there:

**`entityType` sits on the statement, not only on the entity.** Flattening an entity to
`entity . property . value` otherwise loses its schema, with nowhere to recover it from on rebuild.
FtM's own statement model upstream does the same (nomenklatura's row carries `schema` next to
`prop`). It also gives #2202's multi-type requirement for free: rebuilding unions the `entityType`
of an entity's statements, so the type set was never collapsed to a most-specific schema.

**`model` plus a bare `property`, with the namespaced form derived.** #2202 wants a schema column and
the property stored namespaced. Holding `"ftm"` and `"birthDate"` separately, with
`qualifiedProperty()` rendering `ftm:birthDate`, means the two columns cannot drift. #2202's row
mapper strips the prefix using the model column it already has.

**Provenance points at a cell: `(documentId, sheet, rowNumber, column)`,** `sheet` null for a
single-table source. This is the smallest set that answers "where did this value come from" without
ambiguity, since row 12 / column `Name` is ambiguous across a workbook's tabs, and none of the four
is recoverable once the source file is gone. Mapping and run stay reachable through #2202's own run
column. `Row` from #2353 is `(long number, Map<String,String> values)` with `sheet` living in
`RowSourceOptions`, so the executor has all four to hand.

**The id includes provenance**, so each cell is its own fact:
`SHA_384(model, entityId, entityType, property, value, documentId, sheet, rowNumber, column)`,
NUL-joined, null sheet rendered as the empty string. NUL rather than a printable separator because a
cell value can contain any printable character; a value containing a literal NUL would be needed to
forge a collision. `Hasher.SHA_384` (`datashare-api/src/main/java/org/icij/datashare/text/Hasher.java:21`)
matches `Entity.DEFAULT_DIGESTER` (`Entity.java:14`) rather than the SHA1 in the epic's diagram:
`Hasher.SHA_1` exists (`Hasher.java:19`) so the diagram is implementable, but no statement id is ever
exported, so there is no interop reason to break the codebase's convention. Re-running the same
extraction over the same file yields the same rows and therefore the same ids, which is what #2202's
"writing the same data twice changes nothing except last-seen" needs. The same value in two rows
becomes two statements, which is the honest count of the evidence.

`ModelEntity.properties` is `Map<String, List<String>>`, keyed by bare property name: multi-valued as
#2200 requires, and plain strings because #2204 owns value formatting and keeps the raw value
alongside, so a typed value here would be decided in two places.

### The write and read directions

`Statement.of(...)` computes the id and is the write direction. There is deliberately no
`ModelEntity.toStatements(...)`: provenance is per cell, so the column varies from value to value,
and a method taking one `Provenance` would stamp every value of an entity with the same column.
#2204 is mapping-driven and walks property specs one at a time, so it calls `of` per cell it maps.
Checked that no consumer wants the uniform-provenance version: #2203 and #2205 are read-side, #2204
is the only writer.

`ModelEntity.from(Collection<Statement>)` is the read direction: group by property preserving value
order, union the types, and throw on a collection holding more than one `entityId` so that #2202's
grouping stays explicit at the call site. Used by #2202 to rebuild entities, by #2203 to export, by
#2205 to project.

### Loading the model

`FtmTargetModel` reads `ftm/defaultModel-4.10.2.json` from
`datashare-api/src/main/resources` once through `JsonObjectMapper` and builds an immutable
`Map<String, EntityType>`. Per schema: union the properties over its `schemata` list, keep `required`
verbatim, default `abstract` to false, carry `edge` when present.

The file is vendored and checked in, not downloaded at build time: builds stay offline-capable, the
model version is pinned in git, and an upgrade is a reviewable diff instead of whatever `main` held
the day CI ran. A provenance comment on the loader records the upstream URL, the version and the
retrieval date. The `types` section is not parsed: `Property.type` as a string is all #2204 needs to
know a property is a date, and the country, language and topic value tables only matter for the
value-level validation this design defers.

We do not wrap `org.icij.ftm.Model`. It would buy six getters over a map we have already parsed, cost
a 69-instance parents graph we build ourselves, drag in codegen semantics, and force the
implementation out of `datashare-api` where #2201 and #2202 need to see it. `ftm.java` stays what it
is, a build-time generator serving the existing `FtmDocument` endpoint.

### Validation

`validate` returns a list of violations rather than a boolean or a throw, so #2204 can name the
offending row and keep going. It checks that the type exists, that the type is concrete (`Analyzable`,
`Interest`, `Interval`, `Thing` and `Value` cannot be instantiated), that every property key is in
the flattened map, that no property key is a stub, that every `required` entry is present and
non-empty, and that an edge schema has both `edge.source` and `edge.target` set.

The stub check is worth its one line: writing `Person.employers` directly produces a fact FtM's own
tooling infers from the `Employment` edge instead of reading, so a mapping that fills a stub looks
like it worked and silently yields nothing downstream. It is structural, not value-level, so it costs
a flag on `Property` and a lookup.

Values are not checked. `maxLength`, the country and language enums and ISO 8601 date shape all need
a policy on failure (reject the statement, drop the value, warn), and that policy belongs to the
executor's error handling, which does not exist yet. #2204 already owns value formatting with the raw
value kept alongside, so deciding it here would decide it twice.

### One asymmetry, because it shapes #2203

FtM's entity JSON is `{id, schema, properties}` with a single `schema`, while `ModelEntity` carries a
set of types. `serialize` therefore collapses the set to its most specific member, the one whose
`ancestors` contains all the others, which the precomputed list makes a one-liner, and reports a
violation when no such member exists, as for `Person` plus `Company`. `parse` yields a single-type
entity. #2200's round-trip checkbox is a single-type `Person`, so it holds exactly. A multi-type
entity round-trips losslessly through statements, which are the system of record, and lossily through
FtM JSON, which the epic already frames as a disposable projection.

## Files

```
datashare-api/src/main/java/org/icij/datashare/model/TargetModel.java          (+ nested Violation)
datashare-api/src/main/java/org/icij/datashare/model/TargetModelRegistry.java
datashare-api/src/main/java/org/icij/datashare/model/EntityType.java           (+ nested Edge)
datashare-api/src/main/java/org/icij/datashare/model/Property.java
datashare-api/src/main/java/org/icij/datashare/model/ModelEntity.java
datashare-api/src/main/java/org/icij/datashare/model/Statement.java            (+ nested Provenance)
datashare-api/src/main/java/org/icij/datashare/model/ftm/FtmTargetModel.java
datashare-api/src/main/resources/ftm/defaultModel-4.10.2.json                  (vendored, 234KB)
```

Records are nested in the type that owns them, following the `ManifestRepository.ManifestAction`
precedent in `datashare-api`.

## Testing

TDD, red before green for each. Run with
`mvn -pl datashare-api -am test -Dtest=... -Dsurefire.failIfNoSpecifiedTests=false` (comma-separated
class names; a `+` silently passes nothing and reads as green).

1. `TargetModelRegistryTest`: `"ftm"` resolves; the same instance comes back twice; an unknown name's
   message names both the request and the known models.
2. `FtmTargetModelTest`: version is `4.10.2` and 69 types load; `property("Person", "name")` resolves
   to `Thing`'s declaration (`qname: Thing:name`), two levels up; `type("Thing")` is abstract and
   `type("LegalEntity")` is not; `type("Employment").edge()` is `(employee, employer, directed)`;
   `property("Person", "employers").stub()` is true; an unknown type and an unknown property return
   empty.
3. `FtmTargetModelValidationTest`: unknown type, abstract type (`Interest`), undeclared property,
   a stub property (`Person.employers`), missing required where the required property is inherited
   (`Company.name`, declared on `Thing`), edge without a source; and a valid entity yielding an empty
   list.
4. `FtmTargetModelSerializationTest`: a single-type `Person` round-trips through FtM JSON; a
   `{Person, LegalEntity}` entity serializes as `Person`; `{Person, Company}` reports a violation.
5. `StatementTest`: the id is stable across two constructions, changes with the value, changes with
   the column; `qualifiedProperty()` renders `ftm:birthDate`.
6. `ModelEntityTest`: `from` groups multi-values in order and unions types; a mixed-id collection
   throws.

## Out of scope

Value-level validation and cleaning (#2204 owns formatting). The mapping object and its persistence
(#2201). The statement table and its migration (#2202): this design agrees the atom's shape, not its
storage. The exporter seam and streaming a run (#2203). Any second model: the registry holds one
entry, and Schema.org or Wikidata is a new class plus an entry when someone asks.

## Plumbing

Branch `feat/2197-target-model`, closing both issues, following #2353's naming. No `datashare-api`
version bump, matching what #2353 did when it added `org.icij.datashare.tabular` to the same module;
whether that module's separately pinned version (`15.0.1`, `pom.xml`) should move is a question for
the epic rather than for this branch. A fresh worktree needs the gitignored
`datashare-devenv.properties` copied in before the first build, otherwise jOOQ codegen fails on
`datashare-db` in a full reactor run.

## Decisions taken during design

| Question | Decision |
| --- | --- |
| Wrap `org.icij.ftm.Model`? | No. Parse `defaultModel.json` into our own records; `ftm.java` stays the build-time generator behind `FtmDocument`. |
| How does `defaultModel.json` get in? | Vendored and checked in, version-suffixed, with a provenance note. |
| How far does `validate` go? | Structural only, returning a list of violations, including a stub-property check found during spec review. |
| What does provenance carry? | `(documentId, sheet, rowNumber, column)`. |
| How much conversion ships here? | `Statement.of` writing and `ModelEntity.from` reading, plus the id derivation. No `toStatements`: provenance is per cell. |
| Does the statement id include provenance? | Yes. Each cell is its own fact. |
| Interface with one implementation? | Yes, despite being the usual YAGNI target: it is the explicit deliverable of #2197, and #2201 and #2204 resolve a model by name, so the registry exists either way. |
