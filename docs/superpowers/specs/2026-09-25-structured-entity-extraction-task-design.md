# Design: StructuredEntityExtractionTask and factory wiring

Issue [#2206](https://github.com/ICIJ/datashare/issues/2206), part of epic
[#2194](https://github.com/ICIJ/datashare/issues/2194) (import structured entities from tabular data
using a data schema). Designed 2026-09-25.

Every piece of Mode A exists and is tested in isolation, and nothing in `main` constructs any of it.
`JooqStatementRepository`, `JooqExtractionMappingRepository`, `TabularRowReader` and
`EntitiesIndexRebuilder` are referenced only from test sources. This issue gives the epic its first
production caller: one background task that runs a mapping from a document to statements to a
refreshed entities index.

## What changed since the issue was written

The issue text and its comments predate #2204's merged stack, and three of their claims no longer
hold.

- **Retraction already shipped.** `StatementRepository.replace(projectId, runId, documentId, sheet,
  statements)` exists and returns `Replaced(retracted, written)`
  (`JooqStatementRepository.java:71`). Its scope is per-sheet, not the per-document
  `DELETE WHERE prj_id = ? AND doc_id = ?` the issue comments propose, and changeset `049` carries
  the index it needs. Nothing about retraction is left to build here.
- **`MappingExecutor` shipped as `StatementBuilder`.** Its `skipped()` is a `Map<Skip, Long>` over
  four reasons (`ENTITY_UNIDENTIFIED`, `ENTITY_EMPTY`, `CELL_UNREADABLE`, `CELL_MISSING`), not the
  single count the issue describes.
- **The `Stage` entry is #2207's.** #2206's checklist says "adding a pipeline stage if needed" and
  #2207's says the CLI trigger needs "a `Stage` enum entry and its line in `StageRunCommand`'s help
  text". The task is not queue-driven, so it needs no stage at all, and the enum entry that the CLI
  does need belongs with the CLI.

What the issue costed correctly, and what this design therefore owns: no `RepositoryFactory` method,
no Guice binding, and passing the reader's *resolved* sheet, which no `RowSource` reports today.

## Goal

One task class, the wiring that lets Guice build it, and the one reader-API change it needs. No HTTP
route, no CLI command, no `Stage` entry: those are #2207.

## Decisions settled up front

### The index is rebuilt in full at the end of every run

`EntitiesIndexRebuilder.rebuild(projectId)` drops the index and refills it from the statement store,
which is the system of record. Correct by construction: an entity whose last statement was retracted
leaves the index because the index is dropped, not because anything computed that it should be
deleted.

The cost is real and accepted. Every run re-reads and re-indexes the whole project's statements, so
ten mappings on one project cost ten full rebuilds. The alternative, re-reading only the entity ids
a run touched via `StatementRepository.entity`, is one query per entity, which a 100,000-row CSV
turns into 100,000 round trips, and it needs a delete path in the indexer the rebuilder never
needed. The rebuild is the lazy correct option until a project holds enough mappings for it to hurt,
and it can be replaced behind the same call site when it does.

### A reader reports the sheet it resolved

`StatementBuilder` hashes the sheet into the id of every keyless entity, and retraction keys on it.
Both need what was *read*, not what was *asked for*: a mapping asking for sheet `"1"` must hash
alike with one asking for `"Sheet1"` when both land on the same sheet, and two tables of one
document must not collide.

No reader reports it. `RowSource.rows` returns a bare `Stream<Row>`, so this design changes it to
return a record carrying both.

### The write path is always `replace`, never `save`

`save` is an upsert with `onConflictDoNothing`, so re-running the *same* mapping is already a no-op.
What it cannot do is correct a mapping: mappings are immutable, a fix is a new mapping with a new
id, and its statements would insert alongside the stale ones, leaving `entity()` to union both.

`replace` retracts everything the document and sheet contributed before writing, so a correction
supersedes rather than accumulates. The retraction rides the first chunk's transaction, so a run
that dies on row 1 leaves the sheet exactly as it was.

### The run id is the task id

`run_id` is an opaque stamp on the statement row (changeset `047`). The task id is the only
identifier that already names this run, so `taskView.getId()` is passed straight through.

## Components

### `Rows`, a new record in `datashare-api`'s `tabular` package

```java
public record Rows(String sheet, Stream<Row> rows) {}
```

`RowSource.rows(InputStream, RowSourceOptions)` and `TabularRowReader.rows(Project, String, String,
RowSourceOptions)` (`TabularRowReader.java:68`) both return it. What each reader reports:

| Reader | Resolved sheet |
| --- | --- |
| `DelimitedRowSource`, `JsonRowSource` | `null`, one table per source |
| `WorkbookRowSource` | `sheet.getSheetName()`, so asking for `"1"` records `"Sheet1"` |
| `TikaTableRowSource` | the 1-based table index it selected, as a string |

`null` needs no handling downstream. `Statement.Provenance.sheetOrEmpty` folds it to `""` on the way
in, `deleteBySheet` folds it the same way on the way out
(`JooqStatementRepository.java:182`), and `StatementBuilder`'s constructor accepts it.

`TabularRowReader.rows` keeps the `onClose` that closes the underlying source, so callers still use
try-with-resources on the stream inside the record.

### `ExtractionMapping` gains a nullable `rootId`

`TabularRowReader.rows` takes a root id because Elasticsearch routes an embedded document by its
root, and a mapping carries only a `documentId` today. Without it, the reader cannot reach the
embedded sources it was written for (a CSV inside a ZIP, a workbook attached to an email) and fails
with a confusing "no such document".

`rootId` is nullable and means "this is a root document, route on the id itself". The mapping is
persisted as a JSON `definition` blob, so this needs no changeset. #2207's endpoint and CLI pass it
through.

### `StructuredEntityExtractionResult`

```java
@JsonTypeName("StructuredEntityExtractionResult")
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@type")
public record StructuredEntityExtractionResult(long rows, int retracted, int written, int indexed,
                                               Map<StatementBuilder.Skip, Long> skipped)
        implements Serializable {}
```

Modelled on `BatchSearchRunnerResult`, and registered in `TaskResultSubtypes`. The skip counts are
in the result rather than only in the log because a run that reports rows read and statements
written, and nothing else, presents a total loss as a clean import: `ENTITY_UNIDENTIFIED` alone can
account for every row of a file whose key column is blank.

### `StructuredEntityExtractionTask`

```java
@TemporalSingleActivityWorkflow(name = "structured-entity-extraction",
                                activityOptions = @ActivityOpts(timeout = "P1D"))
@TaskGroup(TaskGroupType.Java)
public class StructuredEntityExtractionTask extends DefaultTask<StructuredEntityExtractionResult>
        implements UserTask, CancellableTask
```

Guice supplies `Indexer`, `StatementRepository`, `ExtractionMappingRepository` and
`PropertiesProvider`. The task builds `SourceExtractor`, `TabularRowReader`,
`EntitiesIndexRebuilder` and `DocumentVerifier` itself: neither `SourceExtractor` constructor is
annotated `@Inject`, so Guice cannot supply one, and `DocumentSourceAccess` builds its own for the
same reason.

No service layer sits underneath. Task tests construct the task directly, the way
`DeduplicateTaskTest` does, so extracting the logic would buy a second file and an indirection with
no second caller: #2207 calls the task.

## Data flow

`call()` runs in this order.

1. Read `projectId` and `mappingId` off `taskView.args`; refuse when either is missing.
2. Refuse unless `taskView.getUser()`'s project names contain `projectId`. #2207 gates at the
   endpoint, but a task can also arrive from the CLI or the message bus, and the worker is the last
   place the mapping's project is known before statements are written under it.
3. `mappings.get(projectId, mappingId)`, refusing an absent one.
4. Fetch the `Document` and refuse unless `DocumentVerifier.isRootDocumentSizeAllowed(document,
   project)`, reusing the `embeddedDocumentDownloadMaxSize` limit downloads already apply (default
   `1G`). `TabularRowReader`'s tier-2 Tika fallback buffers a whole document several times over, so
   an unbounded read here is a worker-sized memory risk.
5. `reader.rows(project, mapping.documentId(), mapping.rootId(), mapping.options())`.
6. `new StatementBuilder(mapping, rows.sheet())`.
7. `statements.replace(projectId, taskView.getId(), mapping.documentId(), rows.sheet(),
   rows.rows().flatMap(row -> builder.statements(row).stream()))`, inside try-with-resources on the
   row stream.
8. `rebuilder.rebuild(projectId)`.
9. Return the result and log it at INFO with the skip counts spelled out.

`builder.skipped()` is read after step 7 returns, never before: the stream is lazy, so the counters
are complete only once it has been drained. The `rows` count comes from an `AtomicLong` the flatMap
increments as it goes, for the same reason: nothing knows how many rows a source holds until it has
been read to the end.

## Error handling

Failures propagate; the task swallows nothing.

- `InvalidExtractionMapping` (an `IllegalArgumentException` carrying its `violations`) comes out of
  `StatementBuilder`'s constructor for a mapping that no longer validates, and out of
  `requireColumns` when the source carries none of the mapped columns.
- `UnreadableExtractionMapping` comes out of the repository for a definition that no longer parses.
- Translating either into an HTTP status is #2207's, not this issue's.

A mid-run failure is retried, not cleaned. The retraction rides the first chunk's transaction, so a
run that dies on row 1 leaves the sheet as it was, and a run that dies at row 50,000 leaves it half
rewritten with the index never rebuilt. Re-running retracts and rewrites the whole sheet from
scratch, so the repair for both is the same command.

Cancellation interrupts the task thread, the way `PipelineTask` does with its `volatile taskThread`.
The flatMap checks the interrupt flag and throws rather than truncating: a `takeWhile` would commit
a partial rewrite and then rebuild the index on top of it, reporting a truncated import as a clean
one.

### The two-mappings wipe

Retraction keys on `(project, document, sheet)` and a statement carries no mapping id, so two
mappings over the same sheet retract each other: running the one that pulls companies wipes what the
one that pulls people wrote. This is inherited from #2204's shipped write path and is not fixed
here; fixing it means a `mapping_id` column, a changeset, and a rewrite of the `replace` query.

What this design adds is a signal. `replace` returns only `Replaced(retracted, written)` and the
repository exposes no way to see whose statements the delete removed, so the check rides those two
numbers: **warn when `retracted > written`**, meaning the run removed statements it did not put
back. That catches the wipe and a correction that now yields less, costs no extra query, and stays
quiet on a clean re-run, where the two counts match.

## Wiring

1. `RepositoryFactory` gains `createStatementRepository()` and `createExtractionMappingRepository()`.
   `RepositoryFactoryImpl` implements both through its existing `createRepository(BiFunction)`
   helper: both Jooq classes already take `(DataSource, SQLDialect)`.
2. `CommonMode.configurePersistence` (`CommonMode.java:550`) binds both to instances, alongside the
   four already there.
3. `DatashareTaskFactory` gains
   `StructuredEntityExtractionTask createStructuredEntityExtractionTask(Task<StructuredEntityExtractionResult>, Function<Double, Void>)`.
   The method name must match the class's simple name exactly: `TaskFactoryHelper` looks up
   `create%s` with `(Task.class, Function.class)` by reflection
   (`TaskFactoryHelper.java:16`), so a mismatch fails at runtime, not at compile time.
4. `TaskResultSubtypes` gains an entry for the result record.
5. `TaskType` gains `STRUCTURED_ENTITY_EXTRACTION("org.icij.datashare.tasks.StructuredEntityExtractionTask")`,
   so the task is filterable like every other one.
6. No `Stage` entry and no `CliApp` stage-to-task mapping.

## Testing

- **Readers.** Each existing reader test gains one assertion on the sheet it reports:
  `WorkbookRowSource` returns the sheet name for a mapping that asked for the index, the Tika
  fallback returns the index it selected, the two single-table readers return `null`.
- **Full run.** A small CSV through a real `JooqStatementRepository` against the DB test rules,
  asserting statements written, entities indexed, and the skip counts.
- **Idempotence.** Because the write path is `replace`, a second run reports `retracted = N,
  written = N`, not `written = 0` as a plain `save` with its conditional upsert would. The
  assertion is that the stored statement set and the resulting index are identical, not that the
  counts are zero. A test asserting "the second run writes nothing" asserts the wrong thing and
  fails.
- **Correction.** A second mapping with a new id over the same document and sheet leaves only its
  own statements, and the run warns because `retracted > written`.
- **Refusals.** Unknown mapping, a project the task user is not granted, an oversized root.
- **Cancellation.** An interrupted run throws rather than committing a truncated rewrite, and the
  index is not rebuilt.

## Out of scope

- The REST endpoint, the CLI command, the `Stage` entry, and the mapping authorization decision:
  #2207.
- Translating `InvalidExtractionMapping` into an HTTP status: #2207.
- A `mapping_id` on the statement row, so two mappings can share a sheet.
- Incremental index maintenance, should full rebuilds become the bottleneck.
- The end-to-end integration test and the mapping-format docs: #2208.
