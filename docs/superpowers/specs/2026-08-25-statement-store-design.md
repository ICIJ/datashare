# Design: statement store and mapping persistence

Issues [#2202](https://github.com/ICIJ/datashare/issues/2202) (statement store) and
[#2201](https://github.com/ICIJ/datashare/issues/2201) (mapping persistence), part of epic
[#2194](https://github.com/ICIJ/datashare/issues/2194) (import structured entities from tabular data
using a data schema). Designed 2026-08-25.

The two issues ship as one branch because they share one Liquibase changeset and therefore one jOOQ
codegen cycle, as the epic's [2026-08-19 state
check](https://github.com/ICIJ/datashare/issues/2194#issuecomment-5346473298) planned. Splitting them
would pay the codegen cycle twice and leave the first branch's generated classes stale the moment the
second landed.

## Branch topology

`feat/2202-statement-store` branches off `feat/2197-target-model` (PR
[#2354](https://github.com/ICIJ/datashare/pull/2354)), then merges `feat/2198-tabular-row-source` (PR
[#2353](https://github.com/ICIJ/datashare/pull/2353)).

Two parents because the design needs a type from each: `Statement`, `ModelEntity` and
`TargetModelRegistry` from #2354, and `RowSourceOptions` from #2353.

The stacking risk is not symmetric. #2353 is open, not a draft, and its review findings are already
fixed. #2354 is a draft, 9642 additions, zero submitted reviews, and it owns `Statement`. The
exposure is nonetheless narrow, by construction: this design treats `Statement.id` as an opaque
string, so a review that changes which components feed its SHA-384 costs this branch nothing. Only a
change to `Statement`'s *field set* forces a rebase, and it is confined to the column mapping in
`JooqStatementRepository`.

Recommendation: mark #2354 ready for review and run that review in parallel rather than blocking on
it. It is the epic's Wave 0 gate regardless, and 9.6k unreviewed lines do not get safer by waiting.

## Goal

Two tables and their repositories: the model-neutral substrate that holds extracted facts, and the
persisted artifact that describes how to produce them. Both are storage only. No HTTP surface, no
Guice binding, no production caller, matching how #2354 and #2198 shipped.

## Decisions settled up front

Six. Five are column decisions, which is to say cheap now and expensive or impossible later. The
sixth costs no schema change now but a destructive one later, which puts it in the same class.

### The ontology version lives on the statement, outside the id hash

`Statement` carries `model` (`"ftm"`) but not the ontology version, so nothing associates a stored
fact with FtM 4.10.2.

It goes on the statement as a `model_version` column, filled at write time from
`TargetModelRegistry.get(model).version()`. The statement table is the system of record and outlives
runs, mappings and purged task rows, so it is the only place that can still answer "which ontology
said `ftm:birthDate` was a valid property when this row was written". A mapping is edited in place
and a task row is purgeable, so neither can hold it.

It stays **outside** the id hash. That keeps #2202's own acceptance criterion true across an ontology
upgrade: the same fact keeps the same id. Putting it inside would re-mint every statement id on every
ontology bump, turning a re-run into a full duplicate of the table, and it would require adding a
field to `Statement` on #2354.

Amended 2026-08-25 (fix wave A, ruling 12): the upsert moved to `onConflictDoNothing()`, so a re-save
no longer refreshes `last_seen`, `run_id` or `model_version` on an existing row — those columns are
now written once, on first insert, and never move. An unconditional `DO UPDATE` was measured rewriting
every row and both index entries on a no-op re-run (35 MB → 70 MB → 94 MB over three identical saves),
and nothing outside this branch's own tests reads those columns. `model_version` therefore records the
ontology that first asserted a fact, not the latest to re-assert it.

Consequence worth stating: no change to `Statement`, so this decision creates no #2354 rebase.

### Amended 2026-08-25 (fix wave D)

- **`last_seen` is dropped.** Wave A's `onConflictDoNothing()` left it provably equal to `first_seen`
  on every row, with no writer that could ever move it, on the highest-cardinality table in the
  schema. It comes back in the same changeset that gives the upsert a `doUpdate` clause. `run_id`
  keeps its wave A meaning: the run that first asserted the fact.
- **`entities` has one code path on both dialects.** The wave C fork was justified by a held read
  transaction on a *shared-cache* SQLite database, but the production default
  (`DatashareCliOptions.java:233`) sets no `cache=shared`, and the non-Postgres fallback held an open
  read cursor for the stream's lifetime regardless. One path, one query, no dialect branch.
- **The `Stream<ModelEntity> entities(String)` overload is gone from the interface.** It had no
  caller and its own javadoc documented pool exhaustion as the cost of forgetting `close()`. Only the
  closing `entities(projectId, consumer)` form is public; the stream-returning form is private.
- **`ORDER BY` is `(entity_id, model)`, and property/value are sorted in Java.** Ordering text in SQL
  let the database collation decide an entity's value order: measured, SQLite (BINARY) returns
  `B, C, a, b` where the devenv Postgres (en_US.utf8) returns `a, b, B, C`, so the same statements
  rebuilt to different `ModelEntity` records per deployment.
- **The index stays `(prj_id, entity_id)`.** Measured on the devenv Postgres 15 with 500k rows in one
  project: Index Scan plus Incremental Sort, 36 kB peak sort memory, first row at 0.02 ms, and the
  same plan with statistics deleted. Widening it is not needed. The streaming guarantee does depend
  on Incremental Sort, so **Postgres 13 or later is required**: with `enable_incremental_sort = off`
  the same query becomes a parallel seq scan plus a 26 MB-per-worker external merge, and keyset
  pagination does not rescue it either.
- **`save` counts only positive batch results.** pgjdbc with `reWriteBatchedInserts=true` returns
  `SUCCESS_NO_INFO` (-2) per row, which made the old `sum()` negative. The template's column list and
  each row's positional binds now both come from one `StatementRecord`, so they cannot drift.
- **`ExtractionMapping.validate` delegates to `TargetModel.validate`** against a probe entity carrying
  a placeholder per mapped property, which adds the required-property and edge-endpoint rules the
  hand-rolled check was missing, and walks aliases and property names in sorted order so the violation
  list no longer depends on `Map.copyOf`'s per-JVM iteration salt.
- **A null `options` defaults again.** Wave B's `requireNonNull` made a stored definition without an
  `options` object unreadable, so `get` threw and `list` silently dropped the mapping.
- **Project deletion purges both tables.** `JooqRepository.deleteAll` left every statement and mapping
  of a deleted project behind, so raw source-cell values outlived the deletion and a re-import
  silently no-opped against the surviving rows.

### `original_value` is deferred to #2204

The memo's §10 column list has `original_value`, but the cost-asymmetry argument that made `model`
and the namespaced `property` non-deferrable does not reach it. Nothing writes a statement until
#2206, which is downstream of #2204, so there is no production data to retrofit and no deleted source
to lose.

#2204 owns value formatting and is the only thing that can produce a value distinct from the raw one.
It adds the field to `Statement` and the column in changeset 048, in one pass, exercised by a real
test. Adding it here means a column that cannot be filled or tested.

### Both tables carry `prj_id`

The house convention for per-project data: `document_user_star`, `document_tag`, `batch_search`,
`document_user_mark_read` and `user_history` all carry `prj_id` with an index on it.

#2205 needs it to rebuild one project's `<project>.entities` index, and project deletion needs it to
drop that project's facts. Retrofitting it is the forbidden case, since once a run is gone there is
nothing left that records which project a fact belonged to.

The newest table, `task` (`036-create_task.yml`), has no project column because a task is
cross-project by nature and its project lives in the args JSON. Statements are not: they are project
data like documents.

### `extraction_mapping.user_id` is nullable

The two precedents diverge, and the mapping follows the right one.

`batch_search.user_id` is `nullable: false` (`003-batch-query.yml:22-25`) because a batch search only
ever arrives over HTTP. `task.user_id` has no constraints block (`036-create_task.yml`) because
`CliApp.java:227` starts tasks with `nullUser()`, whose id is null.

A mapping is CLI-creatable, so it matches `task`. `user_id varchar(96)`, nullable, null meaning
`User.nullUser()`.

The column exists now but the repository does not enforce it. That splits the decision along the same
cost line as the rest: the column is the irreversible half and is marginal now, while a mapping with
no recorded author can never be attributed later. Gating is the reversible half and has no caller
yet, and authorization designed with no endpoint to protect is how it gets designed wrong.

### `statement` carries no user

Facts are project data like documents. The mapping is the authored artifact. "Which user produced
this fact" is already answerable through `run_id` to the `task` row, which has `user_id`.

### A mapping is immutable once saved

The epic's stated promise is that the mapping is persisted so the extraction is reproducible. Editing
a mapping in place breaks it: `run_id` still points at a task, but the mapping that task names no
longer describes the statements it produced, and nothing detects the divergence.

So `save` refuses an id that already exists, and changing a mapping means a new row with a new id.
This costs no schema change, because the id is already the primary key, and `run_id` to task args to
mapping id then resolves forever.

The alternative, versioning, would need a `version` column and a composite key. That is the expensive
retrofit: SQLite cannot `ALTER COLUMN`, so it means the table-rebuild dance that
`027-batch-search-projects.yml` had to do. Deciding it now is what makes it cheap.

Two consequences for callers: #2207 surfaces "that id already exists" rather than overwriting
silently, and a typo fix leaves a dead row behind, which is the accepted cost.

## The migration

One changeset, `047-add-extraction-mapping-and-statement.yml`. 046 is taken by
`046-add-tasks-type.yml`, confirmed as the tail of `db.changelog.yml`. Both tables are additive
`createTable` operations, so no dual SQLite/Postgres spelling is needed.

### `statement`

One row per asserted fact, upserted on `id`.

| column | type | null | note |
|---|---|---|---|
| `id` | `varchar(96)` | PK | `Statement.id`, opaque. `Hasher.SHA_384 (96)` (`Hasher.java:21`), the width `task.id` already uses |
| `prj_id` | `varchar(96)` | not null | |
| `run_id` | `varchar(96)` | not null | opaque; no foreign key to `task`, because task rows are purged and SQLite does not enforce foreign keys by default |
| `model` | `varchar(32)` | not null | `"ftm"` |
| `model_version` | `varchar(32)` | not null | from `TargetModelRegistry.get(model).version()` |
| `entity_id` | `text` | not null | |
| `entity_type` | `text` | not null | one type per row |
| `property` | `text` | not null | namespaced, from `Statement.qualifiedProperty()`, e.g. `ftm:birthDate` |
| `value` | `text` | not null | |
| `doc_id` | `varchar(96)` | not null | `Provenance.documentId` |
| `sheet` | `varchar(255)` | not null | `""` when absent, already normalised in `Statement.Provenance` |
| `row_number` | `bigint` | not null | |
| `column_name` | `varchar(255)` | not null | not `column`, which is a reserved word |
| `first_seen` | `datetime` | not null | written on insert only |

One index, `statement_prj_entity` on `(prj_id, entity_id)`. It serves the entity rebuild and covers
any `prj_id`-only scan as its leading column.

No `run_id` index. #2203 is its only prospective consumer, and adding an index later is a cheap
additive migration, unlike adding a column.

**Multi-type entities need no extra column.** #2202 asks that an entity carry more than one type
rather than collapse to a single most-specific schema. That falls out of one type per statement row:
`ModelEntity.from` unions `entityType` across a group (`ModelEntity.java:38`).

**Reading a row back to a `Statement`** un-namespaces the property with
`property.substring(model.length() + 1)`, which is exact, rather than splitting on the first colon,
which a model name containing a colon would break. The read checks the prefix first, so a row written
by anything other than `save` fails with a message naming the row rather than a bare
`StringIndexOutOfBoundsException`.

### `extraction_mapping`

| column | type | null | note |
|---|---|---|---|
| `id` | `varchar(96)` | PK (composite with `prj_id`) | |
| `prj_id` | `varchar(96)` | PK (composite with `id`) | |
| `user_id` | `varchar(96)` | **null** | author; null means `User.nullUser()`, i.e. created from the CLI |
| `name` | `text` | not null | |
| `definition` | `text` | not null | the mapping as JSON |
| `created_at` | `datetime` | not null | |

One index, on `prj_id`. No `user_id` index: `batch_search` has one because it grows unbounded, while
a mappings table holds a handful of rows per project, and nothing queries by user in this pass.

`definition` uses the same JSON-in-text pattern as `task.args`, but with the plain mapper
(`writeValueAsString`/`readValue`) rather than `task.args`'s typed one: the typed mapper's polymorphic
deserialization fails to round-trip `RowSourceOptions.charset()` (`Cannot construct instance of
sun.nio.cs.UTF_8`), and since `definition` always holds the one concrete `ExtractionMapping` type, the
typed mapper's `@type` marker buys nothing here. Not SQL-queryable on SQLite, which has no `jsonb`, so
any filtering happens in Java as the existing repositories already do.

### A deliberate asymmetry

`statement` gets a `model` column; `extraction_mapping` does not, and keeps `model` inside
`definition`.

Statements are queried by model, which is the entire point of a substrate holding several schemas
side by side. Mappings are filtered in Java like every other JSON-in-text row in this schema, so a
duplicate `model` column on `extraction_mapping` would be write-only.

### Timestamps

From `DatashareTime.getInstance().currentTimeMillis()`, as `JooqApiKeyRepository.java:60` does, not
from the database's `now()`. Both dialects then agree, and `DatashareTimeRule` can freeze the clock,
which is what makes the idempotence test assertable.

## The contracts

House pattern: the interface sits in `datashare-api` next to its domain type, the `Jooq*`
implementation in `datashare-db`. `ManifestRepository` is the local precedent for a
persistence-layer contract whose implementation owns atomicity.

### `StatementRepository`

Package `org.icij.datashare.model`, next to `Statement` and `ModelEntity`.

```java
public interface StatementRepository {
    /** Upserts by statement id. An existing row is left untouched: onConflictDoNothing. */
    int save(String projectId, String runId, Collection<Statement> statements);

    /** Every entity of a project, rebuilt by grouping its statements. Lazily read; caller closes. */
    Stream<ModelEntity> entities(String projectId);

    Optional<ModelEntity> entity(String projectId, String entityId);
}
```

`model_version` needs no parameter. The implementation resolves it per statement with
`TargetModelRegistry.get(statement.model()).version()`, a map lookup.

`projectId` and `runId` are plain parameters rather than a wrapper record. They are two strings, and a
record for two strings is ceremony.

### `ExtractionMapping` and `ExtractionMappingRepository`

Package `org.icij.datashare.tabular`, next to `RowSourceOptions`, which the mapping embeds.

```java
public record ExtractionMapping(String id, String projectId, String userId, String name,
                                String model, String documentId, RowSourceOptions options,
                                Map<String, EntityMapping> entities) {

    public record EntityMapping(String type, List<String> keys,
                                Map<String, PropertyMapping> properties) { }

    public record PropertyMapping(List<String> columns, String join, String literal,
                                  String entity, String format) { }

    public List<TargetModel.Violation> validate() { ... }
}

public interface ExtractionMappingRepository {
    /** Saves a new mapping. Returns false when the id already exists: mappings are immutable. */
    boolean save(ExtractionMapping mapping);
    Optional<ExtractionMapping> get(String projectId, String id);
    List<ExtractionMapping> list(String projectId);
    boolean delete(String projectId, String id);
}
```

The shape is FtM's `ftm map` YAML, trimmed to what the epic needs: source, entities, key columns,
per-property fill, value format. `ftm map`'s `database:`/`tables:`/`joins:` query sources, its
`csv_url:` and its filters are dropped as YAGNI. Datashare's source is always a document already in
the index, addressed by `documentId`, and read through #2198's `RowSource`.

`type`, not `schema`. `schema` is both FtM's word and a loaded SQL one, and #2354 already calls the
concept `EntityType`.

`options` is a `RowSourceOptions` verbatim rather than a copy of its six fields, so #2206 hands it
straight to `RowSource.rows(stream, options)` with no translation layer to drift.

`PropertyMapping` stays one flat record rather than a sealed hierarchy. The discriminator in `ftm map`
is which key is present, not a type tag, so Jackson polymorphism would need configuration that
serialises to something other than the format being ported. Its compact constructor enforces that
exactly one of `columns`, `literal` and `entity` is set, that `join` appears only with more than one
column, and that `format` appears only with columns.

The stored form is normalised: `columns` only, never `ftm map`'s singular `column:` alias. Nothing in
this pass reads YAML, so a future importer owns that normalisation.

### Two levels of checking, deliberately split

**In the constructor:** `TargetModelRegistry.get(model)`. An unknown model is unrecoverable, and
`UnknownTargetModel` already carries the clear error #2201 asks for.

**In `validate()`, called on save only:** every `type` resolves through `TargetModel.type(...)` and
every property through `property(type, name)`, returning `TargetModel.Violation`s.

Not in the constructor, so a mapping stored before the ontology moved underneath it still reads back.
A bad mapping is rejected at write time, where the author can fix it.

## The implementations

`JooqStatementRepository` and `JooqExtractionMappingRepository` in `datashare-db`.

**Upsert.** `onConflictDoNothing()` on the composite `(prj_id, id)` key (amended 2026-08-25, fix wave
A, ruling 12; was `onConflict(STATEMENT.ID).doUpdate().set(STATEMENT.LAST_SEEN, ...)`). Batched and
chunked, because one CSV emits 100k+ rows per the memo, and each chunk commits in its own transaction
(amended, ruling 13; was one `transactionResult` over the whole save) — the id is a content hash and
the write idempotent, so a crashed ingest is safely resumable by re-running it, rather than one
transaction over a million rows pinning the Postgres xmin horizon and discarding everything on a late
failure.

**Entity rebuild.** One lazily fetched query filtered on `prj_id` and ordered by `entity_id`,
run-length grouped into `ModelEntity.from(chunk)`. Roughly a 25-line grouping iterator.

The lazier alternative, an `entityIds(projectId)` returning a stream of distinct ids plus one query
per entity, is rejected: #2205 rebuilds a whole project's index and would have to replace an N+1 read
immediately. This is the one place in the design where the higher rung is the right one.

Loading every statement into memory and grouping with `Collectors.groupingBy` is also rejected: the
memo puts 100k+ entities on a single large CSV.

**No wiring.** No `RepositoryFactory` method and no Guice binding. #2206 is the first thing that needs
an instance, and it can hand-wire from `repositoryFactory.getDataSource()` and `guessSqlDialect()` as
`CommonMode.java:536` already does for `JooqTaskRepository`.

## Testing

Both repository tests are `@RunWith(Parameterized.class)` over `DbTestRuleProvider`, so every case
runs on SQLite and on Postgres. Record-level validation tests live in `datashare-api` with no
database.

Written test-first: each case is red before the production code that satisfies it.

**`JooqStatementRepositoryTest`**

- **Idempotence**, which is #2202's own acceptance criterion. Save a set under a frozen
  `DatashareTimeRule`, advance the clock, save the identical set. Assert the row count is unchanged
  and the row, including `first_seen` and `last_seen`, is untouched: a re-save is a genuine no-op.
- Multi-type: two statements sharing an `entity_id` with different `entity_type` values produce one
  entity carrying both types.
- Multi-value: two statements sharing a property with different values produce both values.
- Namespacing round-trip: stored as `ftm:birthDate`, read back bare.
- `model_version` is written as `4.10.2` without any caller supplying it.
- `entities(projectId)` returns only that project's entities.
- Provenance round-trips, including an empty `sheet`.

**`JooqExtractionMappingRepositoryTest`**

- Round-trip on both dialects, including the embedded `RowSourceOptions`.
- A null `user_id` round-trips to `User.nullUser()`.
- `list(projectId)` is project-scoped.
- Saving a mapping whose `validate()` reports violations fails with those violations, rather than
  storing an unusable mapping.
- Saving a second mapping under an existing id returns false and leaves the stored row untouched.

**`ExtractionMappingTest`** (no database)

- An unknown model throws `UnknownTargetModel` at construction.
- `validate()` reports an unknown entity type and an unknown property on a known type.
- `PropertyMapping` rejects zero and more than one of `columns` / `literal` / `entity`, `join` without
  multiple columns, and `format` without columns.

## Codegen

jOOQ codegen reads the live Postgres schema (`datashare-db/pom.xml:171-184`, `${postgresBuildUri}`),
so 047 must be applied to the build database before `mvn generate-sources`. Both tables in one
changeset means one cycle.

A fresh worktree also needs the gitignored `datashare-devenv.properties` copied in, or codegen fails
with "No JDBC Connection configured".

## Out of scope

Named here so they are not rediscovered as gaps:

- **`run_id` index**, when #2203 needs it.
- **`original_value`** column and `Statement` field, in #2204.
- **Project deletion** should drop that project's statements and mappings. #2205 already tracks the
  `<project>.entities` half of the same gap.
- **Gating `ExtractionMappingRepository` on a `User`**, once there is an HTTP surface to protect.
- **YAML import** of `ftm map` files. The stored form is JSON; a future importer normalises.
