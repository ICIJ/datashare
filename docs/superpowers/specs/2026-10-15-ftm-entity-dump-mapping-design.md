# Design: reading an FtM entity dump through the ENTITIES stage

Designed 2026-10-15. Part of the structured entity extraction work: the ENTITIES stage exists and
runs tabular sources, and a FollowTheMoney entity dump is the shape it cannot read.

The shape in question is FtM JSONL: one entity per line, `{"id": ..., "schema": ...,
"properties": {...}}`, every property value an array, every line free to carry a different schema,
and edge entities pointing at other entities by the id the file gives them. It is what the ENTITIES
stage's own exporter writes (`FtmJsonExporter`), so the stage can produce this format and not read
it. The sizes this design is costed against: around a million lines and several hundred megabytes,
with entities and edges mixed in one file.

## Goal

One mapping file, saved and run by the existing ENTITIES stage, builds the entities and the edges
between them out of such a dump, with every edge resolving to the entities the file names.

```
datashare -P <project> stage run --stages ENTITIES --mappingFile dump.json
```

Nothing about the stage, the task, the statement store or the index rebuild changes. Four seams open
in the reader and the builder.

## What blocks it today

Each of these was confirmed against the code, and each kills the run on its own.

- **Arrays are refused.** `JsonRowSource.java:139` throws on any array value, and every line of an
  FtM dump has one. The run dies on row 1.
- **Entity ids are derived.** `StatementBuilder.java:159` hashes
  `sha384(model \0 type \0 keyLiteral \0 sorted key values)`. An edge's endpoint columns hold raw
  FtM ids, which match no hash, so every edge dangles. The type is inside the hash and an edge row
  does not say which schema its endpoint has, so no stand-in alias can be declared for it either.
  In a representative dump both schemata occur on the same endpoint property, roughly nine to one,
  so guessing one type is not an option.
- **The first row must carry every declared column.** `StatementBuilder.java:114-121` throws when
  row 1 lacks one. The first line of a mixed dump is one schema, so any mapping that also covers
  another fails before it reads anything.
- **Sibling schemata share columns.** Two schemata of the same family carry `name`, `country` and
  `address` alike, and every alias is evaluated on every row, so each line of one would also mint an
  entity of the other, keyed on the same id. Nothing in a mapping can currently restrict an alias to
  the rows it is meant for.

## Decisions settled up front

### The id recipe is not touched

Removing the type from the hash would make a stand-in alias work without knowing the endpoint's
type, and would re-identify every statement already stored.
`StatementTest#test_the_id_digest_is_pinned` exists to make that loud. Taking the id verbatim from a
column reaches the same place, costs no migration, and is what the source file was already carrying.

### A verbatim id has no collision guard, by design

`keyLiteral` exists so that two aliases keyed on the same surrogate column do not merge
(`ExtractionMapping.java`, `EntityMapping.keyLiteral`). An id read verbatim has no such guard: two
mappings in one project reading the same id column merge their entities deliberately. That is the
behaviour an FtM dump needs, since its ids are global, and it is the footgun to document on the
option.

### Late failure over mid-read failure

The first-row column check is the only thing standing between a mapping typo and a run that imports
nothing and reports success. It moves to end of run rather than disappearing. `replace` wraps the
whole run in one transaction (`JooqStatementRepository.java:78-81`), so a failure at the end rolls
back the retraction along with everything written, exactly as a failure on row 1 does today.

### One way to restrict an alias, not two

An alias can be restricted either by the row's own declared schema or by requiring one of its
columns to be filled. Requiring a column separates sibling schemata only through the accident of
which columns each one happens to have; testing the schema column reads the row's own declaration of
what it is. Only the second ships. A `required` option on a property remains available as later
work.

## Change 1: `Row` carries several values per column

`Row` becomes `record Row(long number, Map<String, List<String>> values)`.

`Row.values(headers, cells)` wraps each cell in a one-element list, so `DelimitedRowSource`,
`WorkbookRowSource` and `TikaTableRowSource` change in type only: a tabular cell holds one value and
keeps holding one. `JsonRowSource.flatten` maps a JSON array onto the list instead of throwing. An
array holding objects stays refused, since its members have no column name to be mapped under. An
empty array reads as a blank cell, which is what an unfilled FtM property looks like.

`Row.clean` runs per value, as it runs per cell today.

In `StatementBuilder`:

- `cell(row, column)` returns the cleaned values of that column. A value holding a NUL is dropped
  and counted `CELL_UNREADABLE` on its own, rather than blanking the whole column.
- A property filled from `columns` writes one statement per value of each column. The existing
  `putIfAbsent` on statement id absorbs duplicates, which these dumps have: two spellings of one
  name differing only by trailing space clean to one value and so to one statement.
- `join` concatenates across columns in mapping order, then across the values of each column in
  source order. Blank values are skipped, as blank cells are today.
- `fills` treats a column as filled when any of its values is non-blank.
- A **key** column holding more than one value refuses the entity and counts
  `ENTITY_UNIDENTIFIED`. Sorting the values into the hash would pick an identity silently, and no
  mapping over an FtM dump keys on a multi-valued column.
- A date format applies per value.

## Change 2: `idColumn` on an entity mapping

`EntityMapping` gains `idColumn`, mutually exclusive with `keys` and `keyLiteral`. `validate()`
refuses a mapping that sets both, and refuses `idColumn` naming a blank or NUL-bearing column name,
with the wording the existing key-column checks use.

At build time the column's single cleaned value becomes the entity id verbatim. The entity is
refused and counted `ENTITY_UNIDENTIFIED` when that value is blank, when the column holds more than
one value, when it holds a NUL, or when it is longer than `Statement.MAX_ENTITY_ID_LENGTH` (512).
FtM ids are 40 hex characters.

Provenance is unchanged: the statement still records the document, sheet, row number and column it
came from.

This is what makes the edges work. With `idColumn` set to the file's id column on every alias, an
edge row's endpoint property already holds the id of the entity it points at, so it maps straight
from its column with no `entity` cross-reference and no knowledge of the target's type.

## Change 3: a declared column may be absent from a row

`requireColumns` stops failing on row 1. A column missing from a row is counted `CELL_MISSING` once,
as it already is for rows after the first. At end of run, a column that appeared in no row at all
fails the run with `InvalidExtractionMapping`, naming every such column at once.

The guard keeps its purpose, a mapping naming a column the source does not have cannot pass for a
clean import, and reports it after the read instead of before it.

## Change 4: `filters` on an entity mapping

`EntityMapping` gains `filters`, a map of column name to the value that column must hold for the
alias to contribute to a row. An alias whose filters do not all match produces no entity and no
statement for that row, and is not counted as skipped: it was not meant to apply.

Comparison is on cleaned values, the rule every other column comparison follows. A multi-valued
column matches when any of its values matches. `validate()` refuses a blank or NUL-bearing filter
column name or value. A filter column counts as a declared column, so Change 3's end-of-run check
covers a misspelled one.

## The mapping this produces

```json
{
  "id": "dump",
  "projectId": "<project>",
  "name": "FtM dump",
  "model": "ftm",
  "documentId": "<the indexed dump>",
  "entities": {
    "person": {
      "type": "Person",
      "idColumn": "id",
      "filters": {"schema": "Person"},
      "properties": {
        "name": {"columns": ["properties.name"]},
        "firstName": {"columns": ["properties.firstName"]},
        "idNumber": {"columns": ["properties.idNumber"]}
      }
    },
    "company": {
      "type": "Company",
      "idColumn": "id",
      "filters": {"schema": "Company"},
      "properties": {
        "name": {"columns": ["properties.name"]},
        "registrationNumber": {"columns": ["properties.registrationNumber"]}
      }
    },
    "ownership": {
      "type": "Ownership",
      "idColumn": "id",
      "filters": {"schema": "Ownership"},
      "properties": {
        "owner": {"columns": ["properties.owner"]},
        "asset": {"columns": ["properties.asset"]},
        "percentage": {"columns": ["properties.percentage"]}
      }
    }
  }
}
```

## Testing

TDD throughout, each change red before it is green.

- **Change 1** is guarded by `RowSourceEquivalenceTest`, which already pins that all four readers
  agree on the same data; it must keep passing unchanged in meaning. New cases: a JSON array
  becoming several statements, an empty array reading as blank, an array of objects still refused, a
  multi-valued key refusing the entity, `join` across values.
- **Change 2**: an id taken verbatim, each of the four refusals, and `validate()` on `idColumn` with
  `keys`.
- **Change 3**: a column absent from row 1 and present later imports; a column absent from every row
  fails the run and writes nothing, which is the transaction assertion.
- **Change 4**: an alias contributing on matching rows only, a multi-valued filter column, a
  misspelled filter column failing at end of run.
- **End to end**: a synthetic dump of a few thousand lines mixing two entity schemata and an edge
  schema, including at least one edge whose endpoints have different schemata, asserting that both
  ends resolve to indexed entities.

## Performance

A million-line dump produces several million statements, which is an order of magnitude more than
any tabular source the stage has run. None of the four changes touches the write path, so this is
existing behaviour meeting its first input large enough to test it. The plan measures it before
implementing anything, and only then decides whether any of it needs work.

### What the write does today

`replace` opens one transaction (`JooqStatementRepository.java:81`) and sends chunks of 10,000
upserts inside it (`JooqCasbinRuleAdapter.java:24`, Postgres), as one prepared statement bound
repeatedly so the server plans it once. Each row writes four things: the table, the `(id, prj_id)`
primary key, `statement_prj_entity` and `statement_prj_doc`. The upsert's `WHERE` clause keeps a
re-run that changes nothing from rewriting anything.

Postgres caps neither rows nor statements per transaction, so there is no wall to hit. Holding one
open for the length of the run costs three things: vacuum cannot advance past it, a failure near the
end rolls back everything and leaves the dead tuples as bloat, and any statement timeout or dropped
connection costs the whole run.

### The measurement the plan runs first

Push roughly six million synthetic statements through `replace` against a devenv Postgres and record
the wall time and the table and index sizes. This is the first task of the implementation plan, and
its result decides whether the three levers below are needed at all. If the write lands inside a
time the task's `P1D` activity timeout comfortably covers, nothing here is built.

### The levers, if it does not

1. **`reWriteBatchedInserts` on the connection.** Absent from `datashare-devenv.properties.template`
   today, and `inserted()` already carries a comment anticipating the `SUCCESS_NO_INFO` it causes,
   so the read side is ready for it. Confirm first that pgjdbc still rewrites a batch carrying
   `ON CONFLICT DO UPDATE`; if it does, this is a connection-string parameter and nothing more.
2. **A commit per chunk.** Retract in its own transaction, then let each chunk commit on its own.
   Resumable, because statement ids are deterministic and every write is an upsert, so a re-run
   converges on the same rows. The cost is the guarantee named at `JooqStatementRepository.java:78`:
   a sheet would no longer be wholly one run's or wholly the other's, and a crash would leave it
   half written until the run is repeated.
3. **`COPY` into a temporary table, then `INSERT ... SELECT ... ON CONFLICT`.** The largest gain and
   the only Postgres-only option, which the codebase already has precedent for in the entity export.
   Worth building only if 1 and 2 fall short.

### The index rebuild is separate

Every run ends with `EntitiesIndexRebuilder.rebuild`, which drops the project's entities index and
refills it from the whole statement store in chunks of 1,000. For the entity count a dump of this
size yields that is its own several minutes, it runs in full for every mapping run on the project,
and no change here affects it. Measure it alongside the write so the two costs are not confused.

## Risks

- **A verbatim id is source-controlled.** It reaches the `(prj_id, entity_id)` index and the
  entities index. The length bound and the NUL check are what keep it inside what both stores
  accept.

## Out of scope

- Loading FtM entities without a mapping, by writing parsed entities straight to the statement
  store. `TargetModel.parse` already reads this line format, so that route stays open and unbuilt.
- A `required` option on a property, and negative filters.
- Any change to the id hash, the statement store, the entities index or the ENTITIES stage itself.
