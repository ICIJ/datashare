# ENQUEUEIDX: stage-aware NLP-exclusion filter

**Date:** 2026-07-07
**Status:** Approved (design)

## Problem

The `ENQUEUEIDX` stage is run by `EnqueueFromIndexTask`
(`datashare-app/src/main/java/org/icij/datashare/tasks/EnqueueFromIndexTask.java`).
It scrolls Elasticsearch and enqueues document IDs into the queue consumed by the
next stage in the pipeline.

Today the task decides whether to skip documents that have *already* been processed
by an NLP pipeline based purely on whether a `--searchQuery` was supplied
(`EnqueueFromIndexTask.java:66-73`):

```java
if (searchQuery == null) {
    searcher = indexer.search(singletonList(projectName), Document.class)
            .without(nlpPipeline).withSource("rootDocument").limit(scrollSize);   // NLP-exclusion always applied
} else {
    searcher = indexer.search(singletonList(projectName), Document.class, new SearchQuery(searchQuery))
            .withoutSource("content", "contentTranslated").limit(scrollSize);      // no NLP-exclusion
}
```

`nlpPipeline` always has a value (defaults to `CORENLP`, `:56`), and the task has no
awareness of which stage consumes its output. As a result, a no-query run that targets
the **ARTIFACT** stage — e.g. `datashare stage run --stages ARTIFACT,ENQUEUEIDX` — still
silently excludes documents already tagged by the default `CORENLP` pipeline. That
exclusion is meaningless for artifact (embedded-source) extraction and yields a wrong,
too-small subset. The only current workaround is to pass a broad `--searchQuery` to force
the else-branch.

The `.without(nlpPipeline)` filter should be gated on *"does the destination stage care
about NLP?"*, not on *"is there a query?"*.

## Queue-wiring background (why ARTIFACT is reachable at all)

Pipeline stages are chained dynamically by `PipelineHelper`, not hardcoded:

- `--stages` is sorted by `Stage` enum ordinal (`PipelineHelper.java:21`), so
  `ARTIFACT,ENQUEUEIDX` becomes `[ENQUEUEIDX(4), ARTIFACT(9)]`.
- A stage's output queue is derived from the **next stage in the sorted list**
  (`getNextStage`, `PipelineHelper.java:36-40`). With ARTIFACT following ENQUEUEIDX,
  `getNextStage(ENQUEUEIDX) == ARTIFACT`, so ENQUEUEIDX writes to `extract:queue:artifact`,
  which `ArtifactTask` consumes.
- When ENQUEUEIDX has no explicit successor (`--stages ENQUEUEIDX` alone),
  `getNextStage` falls back to `getDefaultNextStage(ENQUEUEIDX)` = `NLP`, i.e.
  `extract:queue:nlp`.

So ENQUEUEIDX already composes with ARTIFACT; only the NLP-exclusion behavior is wrong.

## Behavior contract

The decision to apply `.without(nlpPipeline)` is re-gated on the destination stage.

| Run | Destination | `.without(nlpPipeline)` applied? | Change |
|-----|-------------|----------------------------------|--------|
| No query, `--stages ENQUEUEIDX` (default) | `NLP` (via `getDefaultNextStage`) | Yes | unchanged |
| No query, `--stages ARTIFACT,ENQUEUEIDX` | `ARTIFACT` | **No** — enqueue full set | **fixed** |
| With `--searchQuery`, any destination | any | No (only the query filters) | unchanged |

**Rule for "destination cares about NLP":** destination stage `== Stage.NLP`. This is
deliberately narrow and explicit — any other destination (ARTIFACT, CATEGORIZE, …) gets
no NLP exclusion. It can be broadened later if another NLP-consuming stage needs it.

**Query semantics (decided):** when a `--searchQuery` is present the query *replaces* the
NLP-exclusion (current behavior kept); the two are not combined. Only the no-query path
changes.

**Backward compatibility:** the default pipeline (`SCAN,INDEX,NLP` in web mode, or
`--stages ENQUEUEIDX` in CLI) resolves the destination to `NLP`, so the filter keeps
applying exactly as today.

## Mechanism

Two files change. No CLI options, no queue-wiring changes.

### 1. `PipelineHelper.java`

Promote `getNextStage(Stage)` from `private` to `public` so a task can ask which stage
follows it in the configured pipeline. The method already computes exactly this for queue
naming; only its visibility changes.

```java
public Stage getNextStage(Stage stage) {   // was private
    if (stage == stages.get(stages.size() - 1)) return stage.getDefaultNextStage();
    if (!stages.contains(stage)) return stage.getDefaultNextStage();
    return stages.get(stages.indexOf(stage) + 1);
}
```

### 2. `EnqueueFromIndexTask.java`

Resolve the destination stage once in the constructor and gate the filter in `call()`.

Constructor — add a field:

```java
private final Stage nextStage;
// ...
this.nextStage = new PipelineHelper(propertiesProvider).getNextStage(Stage.ENQUEUEIDX);
```

`call()` — gate `.without(nlpPipeline)` on the destination inside the no-query branch:

```java
Indexer.Searcher searcher;
if (searchQuery == null) {
    QueryBuilderSearcher builder = indexer.search(singletonList(projectName), Document.class);
    if (nextStage == Stage.NLP) {
        builder = builder.without(nlpPipeline);
    }
    searcher = builder.withSource("rootDocument").limit(scrollSize);
} else {
    searcher = indexer.search(singletonList(projectName), Document.class, new SearchQuery(searchQuery))
            .withoutSource("content", "contentTranslated").limit(scrollSize);
}
```

(The exact builder type — `QueryBuilderSearcher` vs the `.without(...)`/`.withSource(...)`
return types — is confirmed against `Indexer` during implementation; the conditional is the
substance.)

## Testing

`datashare-app/src/test/java/org/icij/datashare/tasks/EnqueueFromIndexTaskTest.java`.

Regression (must still pass, behavior unchanged):

- No query, `--stages ENQUEUEIDX`, no docs NLP-tagged → all docs enqueued to
  `test:queue:nlp` (existing test).
- No query, `--stages ENQUEUEIDX`, some docs already tagged by the target NLP pipeline →
  only untagged docs enqueued (exclusion still applies). Add if not already covered.
- With `--searchQuery` (JSON and Lucene string) → matching subset only (existing tests).

New coverage (the fix):

- No query, `--stages ARTIFACT,ENQUEUEIDX`, with some docs already NLP-tagged → **all**
  docs enqueued (NLP exclusion NOT applied), and they land in `test:queue:artifact`.

## Out of scope

- No new CLI option or web-API surface.
- No change to which stages consume which queues.
- No combining of query + NLP-exclusion (explicitly rejected).
- Broadening the "NLP destination" rule beyond `Stage.NLP`.
