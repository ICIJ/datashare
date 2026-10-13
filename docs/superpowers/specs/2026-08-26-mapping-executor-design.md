# Design: mapping executor, mapping to statements

Issue [#2204](https://github.com/ICIJ/datashare/issues/2204), part of epic
[#2194](https://github.com/ICIJ/datashare/issues/2194) (import structured entities from tabular data
using a data schema). Designed 2026-08-26.

The core piece of the epic: turn a persisted mapping plus a file's rows into statements. The
[2026-08-19 correction](https://github.com/ICIJ/datashare/issues/2200) established that
`org.icij:ftm.java:0.3.1` is a build-time generator with no runtime ontology and no mapping engine,
so none of this is reusable from the library.

## Branch topology

`feat/2204-mapping-executor` branches off `feat/2202-statement-store` (PR
[#2359](https://github.com/ICIJ/datashare/pull/2359)).

That single parent covers all three declared dependencies. #2359 already carries #2198's `tabular/`
package (`Row`, `RowSource`, `RowSourceOptions` and the five readers), #2197 and #2200's `model/`
package (`Statement`, `ModelEntity`, `TargetModel`, `TargetModelRegistry`, `FtmTargetModel` and the
vendored `defaultModel-4.10.2.json`), and #2201's `ExtractionMapping`.

The stacking risk is narrow. This design consumes `ExtractionMapping` and `Row` as read-only shapes,
and touches exactly one type on the parent branch: `Statement` gains a nullable component. A review
of #2359 that changes `Statement`'s id hash costs this branch nothing, since the entity id is
computed here and the statement id is treated as opaque.

## Goal

One class that maps a row onto entities and their statements, plus the storage change that lets a
statement keep the value as it was read. No task, no HTTP surface, no Guice binding, matching how
#2197, #2198 and #2202 shipped. #2206 is the first production caller.

## Decisions settled up front

### The entity id is `hash(model, type, sorted key values)`

`Statement.id` is a SHA-384 over nine components, one of which is `entityId`
(`Statement.java:49-56`). A per-run identifier would therefore mint new statement ids on every run
and duplicate the table instead of hitting the `onConflictDoNothing` no-op path
(`JooqStatementRepository.java:87`). The id must be a pure function of the data.

```
entityId = Hasher.SHA_384.hash(String.join("\0", model, type, v1, v2, ... vn))
```

where `v1..vn` are the values of the entity mapping's `keys` columns, each stripped, then sorted in
natural order.

Four properties follow, each of them the reason for one part of that input:

- **The key column *names* are excluded**, so file A's `passport` and file B's `passport_no`
  converge on one entity. The epic's premise is that the same entity shows up across several files
  (#2205), and an id that carries the column name can never satisfy it.
- **The mapping alias is excluded** for the same reason: it is mapping-local vocabulary.
- **The values are sorted**, so two mappings that declare the same keys in a different order agree.
- **The type is included.** A `Person` and a `Passport` keyed on the same national id are two
  entities, not one entity of two types. This matters more than it looks: the
  [2026-08-26 note on #2204](https://github.com/ICIJ/datashare/issues/2204) shows a two-typed entity
  whose types share no common schema is permanently unexportable, and there is no retraction path.

Key values are stripped before hashing because no reader trims cells (`Row.values`,
`Row.java:59-67`, strips nothing; `Row.headers` strips names only), so `"AB123 "` and `"AB123"` would
otherwise be two entities. Stripping is the only normalization: no case folding, no accent folding.
Wrongly splitting an entity is recoverable by a better mapping; wrongly merging two people is not.

NUL-joined for the same reason `Statement.id` is: a cell can hold any printable character. A cell
holding a literal NUL is rejected downstream by `Statement`'s own guard (`Statement.java:38-44`).

### An entity with a blank key is skipped, not defaulted

If any of an entity mapping's key columns is blank on a row, that entity is not identifiable on that
row: it is skipped, and so is every statement that references it. Hashing over an empty string would
collapse every keyless row in the file into one entity, which is worse than dropping them.

### Candidate entities are checked against the model before their statements are kept

`ExtractionMapping.validate` runs on save, not on read, deliberately, so a mapping stored before the
ontology moved underneath it still loads (`ExtractionMapping.java:61-64`). Two consequences:

1. **The constructor re-validates.** `new MappingExecutor(mapping)` throws
   `new InvalidExtractionMapping(mapping.id(), violations)`, the existing type `save` already throws
   (`JooqExtractionMappingRepository.java:40`), when `mapping.validate()` is non-empty. The ontology may have moved
   between save and run; failing at construction reports the same violations the save would have,
   once, instead of one row at a time.
2. **Each candidate entity is validated too.** A row's entity is assembled into a `ModelEntity` and
   passed through `TargetModel.validate`. Violations mean the entity and all its statements are
   dropped and `skipped()` is incremented. This is what removes a half-built `Employment` whose
   `employee` reference did not resolve, without special-casing edges: `TargetModel.validate` already
   knows that an edge type needs both endpoints (`TargetModel.java:77-83`).

The visible consequence, accepted: `Person` and `Company` both declare `required: [name]` in the
vendored 4.10.2 model, so a row whose name column is blank yields no entity at all. A nameless
`Person` carries no recoverable identity, and the epic's own validator is the right authority on it.

This is also where the "look up types and properties through the model interface" checkbox is
satisfied: nothing in the executor names FtM, and both checks go through `TargetModel`.

### A reference to an entity dropped by validation is kept, not cascaded

Pass 1 skips unidentifiable entities and every reference to them, but an entity can also be dropped
*after* its statements are built, by the model check above. An `Employment` referencing a `Person`
whose name column was blank keeps its `employee` statement, pointing at an id no statement in this
file fills.

Kept deliberately. The id is a pure function of the key values, so another file, or another row, can
supply that `Person`'s name later and the two converge, which is the whole point of #2205's
cross-file aggregation. Cascading the drop would throw away a reference that is about to become
valid, and would make the outcome depend on the order aliases happen to be processed in.

### A mapped column the file does not have fails the run

On the first row, every column named by the mapping (keys and property columns alike) is checked
against `row.values().keySet()`, and a missing one throws `IllegalArgumentException` naming all of
them. `Row.values` pads a short row with empty strings, so a typo in a column name is otherwise
indistinguishable from 50,000 blank cells and reports a total loss as a success. This is the same
guard, and the same reasoning, as `Row.headers` refusing a header row whose names are all blank
(`Row.java:38-42`).

### `format` is the input date pattern; the stored value is ISO-8601

`PropertyMapping.format` is a `java.time.format.DateTimeFormatter` pattern describing how the
column is written, for example `dd/MM/yyyy`. The executor parses the cell with it and stores
ISO-8601. Patterns are compiled once in the constructor, so a malformed pattern fails the run at
start rather than at row 40,000.

A value that does not parse is stored **as it was read**, with `originalValue` left null. One `n/a`
in 50,000 rows must not fail a run, and dropping the statement would hide the bad cell instead of
leaving it visible and searchable.

### The raw value is a nullable column, written only when it differs

"Keep the original raw value alongside it" is satisfied by a nullable `original_value`, filled only
when a format actually changed the string. Null reads as "the value is the raw value".

Two points bound what "raw" means and what it costs:

- **Raw means the string `Row` carries**, not the file's byte-level cell. `WorkbookRowSource` returns
  a cell's own value rendered ISO rather than its display string, deliberately
  (`WorkbookRowSource.java:125-174`). Recovering the pre-normalization form would mean a second map
  on `Row` and a change to all five readers on the parent branch, for a value no consumer has asked
  for.
- **It stays outside the id hash.** Inside, a corrected format would re-mint every id; outside, a
  re-run stays a no-op and `onConflictDoNothing` keeps whichever raw arrived first. Nullable also
  means the common case (no format) pays no storage.

### Nothing here writes to the database

The executor returns statements. #2206 owns the run: reading the mapping, opening the `RowSource`,
calling `save`, logging what was skipped. Keeping the executor free of I/O is what makes the whole of
section "Tests" a plain unit test with no database and no Elasticsearch.

## The component

`datashare-api/src/main/java/org/icij/datashare/tabular/MappingExecutor.java`, next to
`ExtractionMapping` and `Row`.

```java
public MappingExecutor(ExtractionMapping mapping)   // throws InvalidExtractionMapping
public List<Statement> statements(Row row)
public long skipped()
```

`skipped()` is a plain `long` field: one executor per run, rows consumed on one thread. #2206 reports
it. Parallel row consumption would need it made atomic, and nothing does that today.

### Pass 1: identity

For each entity alias in the mapping, read its `keys` columns from the row, strip, skip the entity if
any is blank, otherwise compute `entityId` as above. The result is an alias-to-id map for the row.

Two passes rather than one because an `entity` reference is resolved against ids computed from the
same row: an `Employment` referencing `person` and `company` needs both before it can emit anything.

### Pass 2: statements

For each entity that survived pass 1, for each of its mapped properties, emit
`Statement.of(model, entityId, type, property, value, provenance)`:

| `PropertyMapping` shape | value(s) | `Provenance.column` |
| --- | --- | --- |
| one column | the cell | the column name |
| several columns, no `join` | one statement per non-blank cell | that cell's column |
| several columns with `join` | the cells joined by `join` | `String.join(",", columns)` |
| `literal` | the literal | `""` |
| `entity: "other"` | pass 1's id for `other` | `""` |

`PropertyMapping`'s own constructor already guarantees exactly one of `columns`, `literal` and
`entity` is set, that `join` implies more than one column, and that `format` implies columns
(`ExtractionMapping.java:44-58`), so the executor branches on those three without re-checking.

Rules that apply to every shape:

- A blank value emits nothing. `Row.values` pads a short row with empty strings, so an empty cell and
  a missing one are indistinguishable by construction, and `TargetModel.isBlank` already treats both
  as absent (`TargetModel.java:88-90`).
- A reference to an entity skipped in pass 1 emits nothing.
- `Provenance` is `(mapping.documentId(), mapping.options().sheet(), row.number(), column)`. A null
  sheet becomes `""` in `Provenance`'s own constructor (`Statement.java:23`).
- `format` applies to column-sourced values only, and `originalValue` is set through
  `Statement.withOriginalValue` only when parsing changed the string.
- Aliases and property names are walked in sorted order, as `ExtractionMapping.validate` already
  does (`ExtractionMapping.java:61-64`), so the same row always yields the same list in the same
  order. `entities()` and `properties()` are `Map.copyOf` maps, whose iteration order is not stable.
- A `join` skips blank cells before joining, so a blank middle column does not produce a doubled
  separator; all-blank means no statement.
- Parsing is `LocalDate` only: `format` describes a date column, and a pattern that yields no date
  leaves the value as read.

Relationships need no dedicated code path. An `Employment` is an entity mapping whose `employer` and
`employee` properties are `entity` references; the two-pass order is what makes them resolvable, and
`TargetModel.validate` is what rejects one that lost an endpoint.

## Changes to existing code

### `Statement` gains `originalValue`

`datashare-api/src/main/java/org/icij/datashare/model/Statement.java`. A nullable eighth component,
declared after `value` and before `provenance`, excluded from `id(...)`, guarded against NUL like the
others but not against null. Plus:

```java
public Statement withOriginalValue(String raw)
```

A wither rather than a seventh `of` parameter: the id does not change, the executor is the only
caller, and the 18 existing `Statement.of` call sites, all of them tests, do not move.

### Changeset 048

`datashare-db/src/main/resources/liquibase/changelog/changes/048-add-statement-original-value.yml`,
changeset id `83` (047 used 81 and 82), registered at the end of `db.changelog.yml`. One nullable
`original_value text` on `statement`, both dialects.

No index. The delete-by-document sweep discussed on #2204 would want `(prj_id, doc_id)`, and an index
that no query uses is a tax on every insert of the bulk write path.

### `JooqStatementRepository`

`row(...)` writes the column, `READ_FIELDS` and `toStatement(...)` read it. jOOQ regenerates
`StatementRecord` at build time (generated sources are not committed), so reviewers need
`make reset-db` before building, as #2359 already asks.

## Tests

RED before GREEN on every one of them.

**`MappingExecutorTest`** (`datashare-api`), no database, no index:

- Two mappings whose key columns are named differently produce the same entity id for the same
  values; declaring the keys in a different order does too; a different type does not.
- A key value with surrounding whitespace lands on the same entity as one without.
- A row with a blank key column yields no entity, and a reference to it yields no statement.
- Each of the five property shapes emits the values and the provenance in the table above.
- A blank cell emits no statement.
- `format` produces an ISO value with the input kept in `originalValue`; an unparseable value is
  stored as read with `originalValue` null; a malformed pattern throws at construction.
- An entity missing a model-required property is dropped and counted in `skipped()`.
- A mapping that no longer validates against the model throws `InvalidExtractionMapping` at
  construction.

**`MappingExecutionTest`** (`datashare-app`), the issue's last checkbox: a sample CSV fixture read
through `DelimitedRowSource` and a sample mapping, asserting through `ModelEntity.from` that the run
produces the expected `Person`, the expected `Company`, and an `Employment` whose `employer` and
`employee` hold those two entity ids. It lives in `datashare-app` because that is where the readers
are.

**`JooqStatementRepositoryTest`** (`datashare-db`): `original_value` round-trips, and is null when no
format applied. Plus the idempotence proof this issue exists for: saving the executor's output twice
inserts zero rows the second time.

## Out of scope

Deferred deliberately, recorded on the issues that inherit them:

- **Delete-by-document retraction** and the `(prj_id, doc_id)` index it needs. Re-running the *same*
  mapping is already a no-op thanks to the deterministic id; correcting a mapping and re-running
  leaves stale statements. It belongs to #2206, which owns a run.
- **The conditional `model_version` update** (`DO UPDATE ... WHERE model_version <> ?`). Nothing
  reads the column until #2203.
- **Cleaning values beyond dates.** No trimming of stored values, no case folding, no phone or
  country normalization. FtM's own cleaning is not in the library (#2200) and no issue asks for it.
- **Cross-row entity aggregation.** Statements from several rows and several files converge on one
  entity in the store, through `ModelEntity.from`, not in the executor.
