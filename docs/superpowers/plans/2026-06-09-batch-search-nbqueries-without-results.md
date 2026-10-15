# Fix stale `nbQueriesWithoutResults` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `BatchSearchRunner` report the real number of queries that returned no results instead of the stale initial value (which always equals the total query count).

**Architecture:** Count queries with an empty first scroll inside the runner's existing per-query loop, and return that locally-computed count in `BatchSearchRunnerResult`. The DB column `NB_QUERIES_WITHOUT_RESULTS` (maintained independently by `saveResults`) is left untouched.

**Tech Stack:** Java, JUnit 4, AssertJ, Mockito, Maven.

---

### Task 1: Fix the runner test to expect the correct count

This is a bug-fix, so we start by correcting the test that currently encodes the bug. The mock returns documents once then empty, and the same searcher mock serves every query. In `test_run_batch_search` there are two queries (`query1`, `query2`): query1's first scroll yields 2 docs (has results), query2's first scroll is empty (no results). The correct `nbQueriesWithoutResults` is therefore `1`, not `2`.

**Files:**
- Modify/Test: `datashare-app/src/test/java/org/icij/datashare/tasks/BatchSearchRunnerTest.java:65`

- [ ] **Step 1: Update the assertion to the correct expected value**

In `test_run_batch_search`, change line 65 from:

```java
        assertThat(new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(search), progressCb).call().nbQueriesWithoutResults()).isEqualTo(2);
```

to:

```java
        assertThat(new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(search), progressCb).call().nbQueriesWithoutResults()).isEqualTo(1);
```

- [ ] **Step 2: Run the test to verify it now FAILS (proving the bug)**

Run: `mvn -pl datashare-app test -Dtest=BatchSearchRunnerTest#test_run_batch_search`
Expected: FAIL — actual value is `2` (the stale `batchSearch.nbQueriesWithoutResults`), expected `1`. This confirms the bug and that the test now pins the correct behavior.

---

### Task 2: Count queries without results in the runner

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/BatchSearchRunner.java:83`, `:122-126`, `:160`

- [ ] **Step 1: Declare the counter alongside `numberOfResults`**

In `call()`, line 83 currently reads:

```java
        int numberOfResults = 0;
        int totalProcessed = 0;
```

Change to:

```java
        int numberOfResults = 0;
        int totalProcessed = 0;
        int queriesWithoutResults = 0;
```

- [ ] **Step 2: Increment the counter when a query's first scroll is empty**

The per-query loop assigns the first scroll into `docsToProcess` inside the `if (batchSearch.hasQueryTemplate())` / `else` block (lines 111-122), then has:

```java
                long beforeScrollLoop = DatashareTime.getInstance().currentTimeMillis();
                boolean isFirstScroll = true;
                while (docsToProcess.size() != 0 && numberOfResults < MAX_BATCH_RESULT_SIZE - MAX_SCROLL_SIZE) {
```

Insert the check immediately after the `if/else` block closes and before `long beforeScrollLoop`:

```java
                }

                if (docsToProcess.isEmpty()) {
                    queriesWithoutResults++;
                }

                long beforeScrollLoop = DatashareTime.getInstance().currentTimeMillis();
                boolean isFirstScroll = true;
                while (docsToProcess.size() != 0 && numberOfResults < MAX_BATCH_RESULT_SIZE - MAX_SCROLL_SIZE) {
```

(The first `}` shown is the existing closing brace of the `else` block at line 122 — do not add a second one; the new `if` goes between that brace and `long beforeScrollLoop`.)

A non-empty first scroll means the query has hits even if the `MAX_BATCH_RESULT_SIZE` cap later limits saving — this mirrors the DB-side `isFirstScroll` decrement so the two converge.

- [ ] **Step 3: Return the computed counter**

Line 160 currently reads:

```java
        return new BatchSearchRunnerResult(numberOfResults, batchSearch.nbQueriesWithoutResults);
```

Change to:

```java
        return new BatchSearchRunnerResult(numberOfResults, queriesWithoutResults);
```

Leave the null-batch early return (`return new BatchSearchRunnerResult(0, 0);`, line 98) unchanged.

- [ ] **Step 4: Run the corrected runner test to verify it now PASSES**

Run: `mvn -pl datashare-app test -Dtest=BatchSearchRunnerTest#test_run_batch_search`
Expected: PASS — `nbQueriesWithoutResults()` is now `1`.

- [ ] **Step 5: Run the full runner test class to check for regressions**

Run: `mvn -pl datashare-app test -Dtest=BatchSearchRunnerTest`
Expected: PASS — including `test_run_null_batch_search` (asserts on `nbResults` only) and `test_run_batch_search_failure`.

- [ ] **Step 6: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tasks/BatchSearchRunner.java \
        datashare-app/src/test/java/org/icij/datashare/tasks/BatchSearchRunnerTest.java
git commit -m "fix: report actual nbQueriesWithoutResults in batch search task result"
```

---

## Self-Review notes

- **Spec coverage:** Change 1 (runner counter + return) → Task 2. Change 2 (test fix) → Task 1. Out-of-scope items (DB column, resource test) correctly left untouched.
- **Type consistency:** counter named `queriesWithoutResults` throughout; `BatchSearchRunnerResult(int nbResults, int nbQueriesWithoutResults)` signature matches `BatchSearchRunnerResult.java:11`.
- **No placeholders:** all steps show exact code and exact commands.
