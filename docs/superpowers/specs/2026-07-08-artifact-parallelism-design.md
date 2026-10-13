# ARTIFACT stage parallelism

Date: 2026-07-08
Status: Approved design, pending implementation plan

## Problem

The `ARTIFACT` stage builds the embedded-source artifact cache by draining a queue of
document ids and calling `SourceExtractor.extractEmbeddedSources` on each one. On a
production run over ~50,000 documents the stage is very slow.

`ArtifactTask.call()` runs a single `while` loop on the task thread:

```java
while ((docId = inputQueue.poll(pollingInterval, TimeUnit.SECONDS)) != null) {
    Document doc = getDocument(indexer, project.name, docId, sourceExcludes);
    ...
    extractor.extractEmbeddedSources(project, doc);
}
```

There is no thread pool. The service is launched with `--parallelism 32`, but
`ArtifactTask` never reads that option, so exactly one document is processed at a time
while 31 cores sit idle. `IndexTask` and `ExtractNlpTask` already parallelize; `ArtifactTask`
is the outlier.

## Goal

Make `ArtifactTask` process documents concurrently across a worker pool sized by
`--parallelism`, so a batch `ENQUEUEIDX,ARTIFACT` run uses the whole box.

## Non-goals

Explicitly out of scope for this change (decided during brainstorming):

- **No in-flight root-document guard.** When several queued documents share the same root
  document, workers may parse that root more than once and may write the same digest
  artifact concurrently. We accept this. Writes target content-addressed paths
  (`<artifactDir>/<project>/<xx>/<yy>/<digest>/raw`), so concurrent writers produce
  identical bytes; a last-writer-wins overwrite is harmless. Deduplicating at enqueue time
  is a possible future optimization, not part of this change.
- **No progress-callback wiring.** The task already ignores the `updateCallback`; we keep it
  that way.
- **No changes to `ENQUEUEIDX`, `extract-lib`, or `SourceExtractor`'s public surface.**

## Design

### Worker pool

`ArtifactTask.call()` builds a fixed pool of `parallelism` threads and submits `parallelism`
identical workers. Each worker runs the loop that exists today, unchanged in its per-document
logic:

1. `docId = inputQueue.poll(pollingInterval, SECONDS)`; exit when it returns `null`.
2. `getDocument(indexer, project.name, docId, sourceExcludes)`; if `null`, increment the
   skipped counter and continue.
3. `extractor.extractEmbeddedSources(project, doc)`; increment the processed counter.
4. Any `Throwable` from steps 2-3 is caught and logged (`error in ArtifactTask loop`); the
   worker continues to the next id. One bad document never kills a worker or the task.

The `poll` call sits in the loop condition, outside the per-document `try/catch`, so an
`InterruptedException` propagates out of the worker and ends it cleanly (see Cancellation).

### SourceExtractor per worker

`SourceExtractor` is cheap to construct and holds per-call digester/extractor state created
inside `extractEmbeddedSources`. To avoid any shared-state questions, **each worker constructs
its own `SourceExtractor`** rather than sharing one instance. This matches the "isolated unit"
principle and removes the need to audit thread-safety of the shared instance.

### Parallelism option

Reuse the existing `--parallelism` (`PARALLELISM_OPT`) option that `IndexTask` reads. In
`ArtifactTask`:

```java
parallelism = propertiesProvider.get(PARALLELISM_OPT).map(Integer::parseInt).orElse(1);
```

Effective default, by mode (this nuance is intentional and must be preserved):

- **CLI batch mode** (this use case): `joptsimple` bakes the `--parallelism` CLI default
  (`availableProcessors`) into the properties map even when the flag is omitted (see
  `DatashareCli.asProperties`, which sets a property whenever the option spec has a value,
  including its declared default). So in CLI mode the property is always present and the
  worker count is the user's explicit value (32 here) or the CLI default of `availableProcessors`.
  `.orElse(1)` is effectively unreachable in CLI mode.
- **Server / on-demand mode**: `ArtifactTask` is created from a `Task`'s args map, which does
  not go through the CLI parser and normally omits `parallelism`. There `.orElse(1)` applies,
  keeping the single-document-at-a-time behavior that the interactive artifact path relies on.

Net effect: batch runs parallelize; the on-demand path stays single-threaded. No new option is
introduced.

### Counters and return value

`nbDocs` and `nbSkipped` become `AtomicLong` shared across workers. The method returns the
total processed count (`nbDocs`), unchanged in meaning. The end-of-run summary logs
(`N document(s) could not be retrieved ...` and `exiting ArtifactTask loop after processing N
document(s)`) are emitted once by the main thread after all workers finish, aggregating across
workers.

### Cancellation and shutdown

- The main task thread submits the workers, then awaits their completion (e.g. `invokeAll`, or
  submit + `future.get()` for each).
- `PipelineTask.cancel(boolean)` interrupts the task thread (existing behavior). The task thread
  responds by calling `executor.shutdownNow()`, which interrupts workers blocked in `poll`,
  then returns. Workers observe the interrupt and exit.
- The pool is always shut down in a `finally` block so no threads leak, whether the task
  completes normally, is cancelled, or throws.
- A worker that dies on an unexpected error (outside the per-document catch) must not hang the
  task: the main thread stops waiting once every worker future has completed (normally or
  exceptionally) and still returns the processed count. Remaining workers keep draining.

### Logging

Name pool threads `artifact-worker-%d` so per-worker log lines are attributable. The existing
startup log line (`creating artifact cache in {} for project {} from queue {} with polling
interval {}s`) gains the worker count.

## Testing (TDD)

Build on the existing `ArtifactTaskTest` (uses `MemoryDocumentCollectionFactory`, `MockIndexer`,
and a real `embedded_doc.eml` resource). New/updated tests:

1. **Parallel drain processes every id.** Enqueue several ids resolving to the embedded doc,
   run with `parallelism=2`, assert the returned count equals the number enqueued and that the
   artifact files exist. (Extends `test_create_artifact_cache_one_file`.)
2. **Skipped docs still counted under parallelism.** With `parallelism=2`, enqueue an unknown
   id plus a good id; assert processed count, the WARN skip log, and the ERROR summary
   (`1 document(s) could not be retrieved ...`) still appear, and that no `error in ArtifactTask
   loop` ERROR is logged. (Parallel variant of `test_skip_document_not_found_in_index`.)
3. **Per-document failure is non-fatal.** Make one document's extraction throw (e.g. an id that
   resolves to a document whose source path is missing) alongside a good id; assert the good id
   is still processed, the count reflects only successes, and the error is logged.
4. **Workers actually run concurrently.** Enqueue two ids whose extraction blocks on a shared
   latch (via a test seam that counts down and waits); with `parallelism=2`, assert both
   extractions are in flight simultaneously within a timeout. This is the test that proves the
   pool parallelizes rather than serializing. If a clean latch seam is not reachable without an
   invasive change, fall back to asserting distinct `artifact-worker-*` thread names observed
   during the run.
5. **Default stays single-threaded when the option is absent.** Construct the task with a
   properties map that omits `parallelism` (server-mode shape) and assert it still drains the
   queue correctly (behavioral guard against a regression that would parallelize the on-demand
   path).

Each test carries a `@Test(timeout = ...)` as the existing ones do.

## Operational notes (for the current production run)

- Once 32 workers run, `OMP_THREAD_LIMIT=1` is correct: 32 single-threaded Tesseract/OpenMP
  calls beat a smaller number of contended multi-threaded ones. No env change needed.
- Per-document cost stays high because OCR-produced documents are re-parsed with OCR on
  (`SourceExtractor.useOcr`). Parallelism is the win here, not per-doc speedup.
- Watch heap: 32 concurrent embedded extractions raise peak memory versus one. `-Xmx36g` should
  be ample, but if OOMs appear, lowering `--parallelism` is the first lever.
