# ENQUEUEIDX Stage-Aware NLP-Exclusion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `EnqueueFromIndexTask` decide whether to skip already-NLP'd documents based on the destination stage (`== Stage.NLP`) instead of on whether a `--searchQuery` was supplied, so that `--stages ARTIFACT,ENQUEUEIDX` (no query) enqueues the full document set.

**Architecture:** `EnqueueFromIndexTask` already receives the pipeline config via `PropertiesProvider`. It will resolve its destination stage once (constructor) using `PipelineHelper.getNextStage(Stage.ENQUEUEIDX)` — the same method that already computes queue chaining — and gate the `.without(nlpPipeline)` filter on that stage inside the existing no-query branch. The query branch is untouched.

**Tech Stack:** Java, Maven, JUnit 4, Elasticsearch (via `ElasticsearchRule`), Google Guice.

## Global Constraints

- Project version: `21.7.0` (root `pom.xml`).
- No new CLI options, no web-API changes, no queue-wiring changes.
- `--searchQuery` semantics unchanged: when a query is present it *replaces* the NLP-exclusion (the two are never combined).
- The "destination cares about NLP" rule is exactly `destinationStage == Stage.NLP`.
- Tests in `EnqueueFromIndexTaskTest` require a running Elasticsearch (the class uses `ElasticsearchRule`). Install locally with `make elasticsearch` if needed.
- Test/build command for this environment (offline, DB-free — see project memory):
  ```
  mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app \
    -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=EnqueueFromIndexTaskTest test
  ```

---

### Task 1: Stage-aware NLP-exclusion in ENQUEUEIDX

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/PipelineHelper.java:36` (change method visibility `private` → `public`)
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/EnqueueFromIndexTask.java` (add `nextStage` field + gate the filter in `call()`)
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/EnqueueFromIndexTaskTest.java`

**Interfaces:**
- Consumes: `PipelineHelper.getNextStage(Stage)` → returns the `Stage` that follows the given stage in the configured, ordinal-sorted pipeline (falling back to `Stage.getDefaultNextStage()` when the stage is last or absent). Already exists; only its visibility changes.
- Produces: no new public API. Behavior change only: `EnqueueFromIndexTask` applies `.without(nlpPipeline)` iff `getNextStage(Stage.ENQUEUEIDX) == Stage.NLP` and no `searchQuery` is set.

Background on why the ARTIFACT test's output queue is `test:queue:artifact`: with `--stages ARTIFACT,ENQUEUEIDX`, `PipelineHelper` sorts to `[ENQUEUEIDX, ARTIFACT]`, so `getNextStage(ENQUEUEIDX) == ARTIFACT` and the output queue is `<queueName>:artifact`.

- [ ] **Step 1: Write the failing test for the ARTIFACT path**

Add to `EnqueueFromIndexTaskTest.java`. Docs are tagged with `CORENLP` (the default `nlpPipeline`). Before the fix, the no-query branch applies `.without(CORENLP)` and excludes all of them → the artifact queue would be empty/absent. After the fix, the destination is `ARTIFACT`, so no exclusion applies and all 5 docs are enqueued.

```java
    @Test
    public void test_no_query_targeting_artifact_ignores_nlp_pipeline_filter() throws Exception {
        for (int i = 0; i < 5; i++) {
            indexer.add(es.getIndexName(), createDoc("doc" + i)
                    .with(Pipeline.Type.CORENLP).with(project(es.getIndexName())).build());
        }
        Map<String, Object> properties = Map.of(
                "defaultProject", es.getIndexName(),
                "stages", "ARTIFACT,ENQUEUEIDX",
                "queueName", "test:queue");
        MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();
        EnqueueFromIndexTask enqueueFromIndex = new EnqueueFromIndexTask(factory, indexer,
                new Task<>(EnqueueFromIndexTask.class.getName(), new User("test"), properties), null);
        enqueueFromIndex.call();
        assertThat(factory.queues.get("test:queue:artifact")).hasSize(5);
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```
mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app \
  -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=EnqueueFromIndexTaskTest#test_no_query_targeting_artifact_ignores_nlp_pipeline_filter test
```
Expected: FAIL — `test:queue:artifact` is null or empty (the `.without(CORENLP)` filter excluded all 5 docs), so `hasSize(5)` fails (likely a NullPointerException on a missing queue or `hasSize(0)`).

- [ ] **Step 3: Make `getNextStage` public in `PipelineHelper`**

In `datashare-api/src/main/java/org/icij/datashare/PipelineHelper.java`, change line 36 from:
```java
    private Stage getNextStage(Stage stage) {
```
to:
```java
    public Stage getNextStage(Stage stage) {
```
(Body unchanged.)

- [ ] **Step 4: Resolve the destination stage in the `EnqueueFromIndexTask` constructor**

In `datashare-app/src/main/java/org/icij/datashare/tasks/EnqueueFromIndexTask.java`:

Add the import (with the other `org.icij.datashare` imports):
```java
import org.icij.datashare.PipelineHelper;
```

Add a field next to the existing fields (near line 42):
```java
    private final Stage nextStage;
```

At the end of the constructor (after line 60, `this.searchQuery = ...`), add:
```java
        this.nextStage = new PipelineHelper(propertiesProvider).getNextStage(Stage.ENQUEUEIDX);
```

- [ ] **Step 5: Gate the `.without(nlpPipeline)` filter in `call()`**

Replace the no-query branch of the `if (searchQuery == null)` block (currently `EnqueueFromIndexTask.java:67-69`) so the filter only applies when the destination is the NLP stage:

```java
        Indexer.Searcher searcher;
        if (searchQuery == null) {
            Indexer.QueryBuilderSearcher builder = indexer.search(singletonList(projectName), Document.class);
            if (nextStage == Stage.NLP) {
                builder = builder.without(nlpPipeline);
            }
            searcher = builder.withSource("rootDocument").limit(scrollSize);
        } else {
            searcher = indexer.search(singletonList(projectName), Document.class, new SearchQuery(searchQuery))
                    .withoutSource("content", "contentTranslated").limit(scrollSize);
        }
```

(`indexer.search(indexes, Document.class)` returns `Indexer.QueryBuilderSearcher`; `.without(Pipeline.Type...)` returns `QueryBuilderSearcher`; `.withSource(...)`/`.limit(...)` return `Indexer.Searcher`. Confirmed against `Indexer.java:23,73,76,84-86`.)

- [ ] **Step 6: Run the new test to verify it passes**

Run:
```
mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app \
  -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=EnqueueFromIndexTaskTest#test_no_query_targeting_artifact_ignores_nlp_pipeline_filter test
```
Expected: PASS — `test:queue:artifact` has size 5.

- [ ] **Step 7: Add a regression test proving the NLP path still excludes**

This guards the unchanged NLP behavior: with `--stages ENQUEUEIDX` (destination resolves to `NLP` via `getDefaultNextStage`) and docs already tagged by the target pipeline, they must be excluded. Add to `EnqueueFromIndexTaskTest.java`:

```java
    @Test
    public void test_no_query_targeting_nlp_still_excludes_already_processed() throws Exception {
        for (int i = 0; i < 5; i++) {
            indexer.add(es.getIndexName(), createDoc("doc" + i)
                    .with(Pipeline.Type.CORENLP).with(project(es.getIndexName())).build());
        }
        Map<String, Object> properties = Map.of(
                "defaultProject", es.getIndexName(),
                "stages", "ENQUEUEIDX",
                "queueName", "test:queue",
                NLP_PIPELINE_OPT, Pipeline.Type.CORENLP.name());
        MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();
        EnqueueFromIndexTask enqueueFromIndex = new EnqueueFromIndexTask(factory, indexer,
                new Task<>(EnqueueFromIndexTask.class.getName(), new User("test"), properties), null);
        enqueueFromIndex.call();
        assertThat(factory.queues.get("test:queue:nlp")).isNullOrEmpty();
    }
```

Note: `factory.queues.get(...)` may return `null` when nothing was enqueued (the output queue is created lazily on first `add`). `isNullOrEmpty()` covers both. If `MemoryDocumentCollectionFactory` always pre-creates the queue, this still holds (empty). If the assertion helper is unavailable, use `assertThat(factory.queues.getOrDefault("test:queue:nlp", java.util.Collections.emptyList())).isEmpty();`.

- [ ] **Step 8: Run the full test class to verify all tests pass (new + existing)**

Run:
```
mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app \
  -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=EnqueueFromIndexTaskTest test
```
Expected: PASS — all methods green, including the pre-existing `test_size_of_search`, `test_with_query_body`, `test_with_query_string`, `test_log_displays_correct_document_count`.

- [ ] **Step 9: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/PipelineHelper.java \
        datashare-app/src/main/java/org/icij/datashare/tasks/EnqueueFromIndexTask.java \
        datashare-app/src/test/java/org/icij/datashare/tasks/EnqueueFromIndexTaskTest.java
git commit -m "feat: make ENQUEUEIDX NLP-exclusion stage-aware so ARTIFACT enqueues the full set"
```
