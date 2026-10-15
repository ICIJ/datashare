# Fix stale `nbQueriesWithoutResults` in the batch-search task result

## Problem

`GET /api/task/:task_id` exposes `.results.value.nbQueriesWithoutResults`. This value is
always equal to the total number of queries in the batch search, regardless of how many
queries actually returned results.

## Root cause

`BatchSearchRunner.call()` returns its result at `BatchSearchRunner.java:160`:

```java
return new BatchSearchRunnerResult(numberOfResults, batchSearch.nbQueriesWithoutResults);
```

The `batchSearch` object was fetched at the start of the run (`BatchSearchRunner.java:95`,
`repository.get(...)`), before any query executes. The lifecycle of
`nbQueriesWithoutResults` is:

1. **At creation/save**, the DB column `NB_QUERIES_WITHOUT_RESULTS` is seeded to
   `queries.size()` — the pessimistic assumption that *every query starts without results*
   (`BatchSearch.java:75` passing `queries.size()`, persisted by `save()` at
   `JooqBatchSearchRepository.java:63`).
2. **During the run**, each time a query gets its first page of hits,
   `saveResults(..., isFirstScroll=true)` decrements the DB column by 1
   (`JooqBatchSearchRepository.java:96`).

So the DB column is correct *after* the run, but the in-memory `batchSearch` captured at
line 95 still holds the **initial** value (`= queries.size()`). That stale value is what is
returned into the task result — hence it always equals the number of queries.

The existing test `BatchSearchRunnerTest.java:65` encodes the bug: query1 returns docs and
query2 returns none, yet it asserts `nbQueriesWithoutResults == 2` (should be 1).

## Chosen approach

**Count it in the runner loop.** The runner already iterates every query and has all the
information in hand. Track whether each query's first scroll returned any documents and
return a locally-computed count. Self-contained, no extra DB round-trip, returns exactly
what the run observed. This converges with the authoritative DB column, which is left
untouched.

(Rejected alternative: re-read the value from the DB after the run. It keeps the DB as the
single source of truth but adds a query and couples the result to the DB-side decrement
logic and its cancel/requeue edge cases.)

## Changes

### `BatchSearchRunner.java`

- Declare a counter alongside `numberOfResults` (line 83): `int queriesWithoutResults = 0;`
- Inside the per-query loop, right after the first scroll is assigned to `docsToProcess`
  (after the `if/else` block, before the `while` at line 126):

  ```java
  if (docsToProcess.isEmpty()) {
      queriesWithoutResults++;
  }
  ```

  A non-empty first scroll means the query has hits (even if the `MAX_BATCH_RESULT_SIZE`
  cap later limits saving), mirroring the DB-side `isFirstScroll` decrement logic so the
  two converge.
- Change line 160 to return the computed value:

  ```java
  return new BatchSearchRunnerResult(numberOfResults, queriesWithoutResults);
  ```
- The null-batch early return at line 98 (`new BatchSearchRunnerResult(0, 0)`) stays as-is.
  The cancel/exception paths exit via `throw` and never return a result, so they need no
  counting.

### `BatchSearchRunnerTest.java`

- `test_run_batch_search` (line 65): fix the assertion from `isEqualTo(2)` to
  `isEqualTo(1)`. With query1 returning docs and query2 returning none, exactly one query
  has no results. This corrects the expectation that currently encodes the bug.

## Out of scope

- The DB column `NB_QUERIES_WITHOUT_RESULTS` and its `saveResults` decrement
  (`JooqBatchSearchRepository.java:96`) are already correct and remain the persistence-layer
  source of truth.
- `BatchSearchResourceTest.java:281` exercises that DB path and is unaffected.

## Testing

- Existing `BatchSearchRunnerTest` covers the runner; the corrected assertion verifies the
  fix.
- `test_run_null_batch_search` asserts on `nbResults` only and should continue to pass.
