# Remove the POISON Pill Mechanism Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the in-band POISON sentinel from every datashare pipeline stage so consumers stop when their input queue is empty, and move the end-of-stream guarantee to the launcher, which awaits each stage's task before starting the next.

**Architecture:** Stage tasks are sequenced by `CliApp.runPipeline`, so a consumer stage's input queue is complete before the consumer starts. Every consumer loop becomes `while ((entry = queue.poll()) != null)`, which is correct for any number of workers and leaves nothing in the queue between runs. `PATH_POISON` survives only as a skip target so a queue written by a pre-upgrade run stays processable; `STRING_POISON` is deleted.

**Tech Stack:** Java 21, Maven multi-module, Guice, JUnit 4 with fest-assert and Mockito, Redis (Redisson) and in-memory document queues from extract-lib, Elasticsearch.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-27-remove-poison-pill-design.md`.
- extract-lib is NOT modified. `DocumentQueueDrainer.drain(Path poison)` stays for extract-cli; datashare stops calling it. No dependency bump.
- Single-module test command: `mvn -pl <module> -am test -Dtest=<ClassName> -Dsurefire.failIfNoSpecifiedTests=false`. Without `-Dsurefire.failIfNoSpecifiedTests=false` the other modules fail the run.
- Integration tests (`*IntTest`, anything with `ElasticsearchRule`) need Elasticsearch at `elasticsearch:9200` and Redis at `redis:6379`. Both run in the devenv already.
- `datashare-devenv.properties` must exist at the worktree root. It is gitignored and per-developer, so a fresh worktree does not have it: copy it from the main checkout. Without it `${postgresBuildUri}` is unresolved and the build dies in `datashare-db` jOOQ codegen ("No JDBC Connection configured") before any test runs. Postgres itself must be up at `postgres:5432` for that codegen.
- `/dev/tcp/<host>/<port>` reachability probes are blocked by the sandbox and report every service as closed. Use `curl` or `pg_isready` instead.
- Transitional retro-compat code carries a `ponytail:` comment naming 21.16 as the removal version (current `pom.xml` version is 21.15.4).
- Commit each task with the `/commit` skill. Suggested messages are given per task. No Claude attribution in commit messages.
- No em dash characters in comments or commit messages.

---

## File Structure

Modified, main:

- `datashare-app/src/main/java/org/icij/datashare/CliApp.java` - owns the stage barrier (`runPipeline` + `TASK_CLASSES`).
- `datashare-app/src/main/java/org/icij/datashare/mode/CommonMode.java` - warns when `taskWorkers > 1` weakens that barrier.
- `datashare-app/src/main/java/org/icij/datashare/tasks/PipelineTask.java` - keeps `PATH_POISON` as a skip target, loses `STRING_POISON`.
- `.../tasks/ScanTask.java`, `.../tasks/DeduplicateTask.java`, `.../tasks/IndexTask.java` - the Path queue chain.
- `.../tasks/EnqueueFromIndexTask.java`, `.../tasks/ArtifactTask.java` - the ARTIFACT chain.
- `.../tasks/CategorizeTask.java`, `.../tasks/ExtractNlpTask.java` - the String queue chain.
- `datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchSpewer.java` - the fifth emitter, `close()` puts `"POISON"` on the NLP queue.

Modified, test: `CliAppTest`, `ScanTaskTest`, `DeduplicateTaskTest`, `IndexTaskIntTest`, `EnqueueFromIndexTaskTest`, `ArtifactTaskTest`, `CategorizeTaskIntTest`, `ExtractNlpTaskIntTest`.

Task order is dictated by the compiler and by keeping every commit green: the barrier lands first (Task 1), then each queue chain independently (Tasks 2, 3, 4). `ArtifactTask` reads `ExtractNlpTask.NB_MAX_POLLS`, so ARTIFACT (Task 3) must precede the NLP cleanup (Task 4), which deletes that constant.

---

### Task 1: Sequence pipeline stages in CliApp

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/CliApp.java:177-216`
- Modify: `datashare-app/src/main/java/org/icij/datashare/mode/CommonMode.java:129`
- Test: `datashare-app/src/test/java/org/icij/datashare/CliAppTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `static void CliApp.runPipeline(TaskManager taskManager, PipelineHelper pipeline, Properties properties) throws Exception` and `static final Map<Stage, Class<?>> CliApp.TASK_CLASSES`. Tasks 2 to 4 rely on the guarantee it provides (a stage's input queue is complete before the stage starts) but call no code from it.

- [ ] **Step 1: Write the failing tests**

Append to `datashare-app/src/test/java/org/icij/datashare/CliAppTest.java`, inside the class:

```java
    @Test
    public void test_run_pipeline_starts_a_stage_only_after_the_previous_one_is_done() throws Exception {
        TaskManager mockedManager = mock(TaskManager.class);
        when(mockedManager.startTask(eq(ScanTask.class), any(), any())).thenReturn("id-scan");
        when(mockedManager.startTask(eq(IndexTask.class), any(), any())).thenReturn("id-index");
        doReturn(doneTask()).when(mockedManager).getTask("id-scan");
        doReturn(doneTask()).when(mockedManager).getTask("id-index");
        Properties properties = new Properties();
        properties.setProperty("stages", "SCAN,INDEX");

        CliApp.runPipeline(mockedManager, new PipelineHelper(new PropertiesProvider(properties)), properties);

        InOrder inOrder = inOrder(mockedManager);
        inOrder.verify(mockedManager).startTask(eq(ScanTask.class), any(), any());
        inOrder.verify(mockedManager).awaitTermination(anyInt(), any(), eq(Set.of("id-scan")));
        inOrder.verify(mockedManager).startTask(eq(IndexTask.class), any(), any());
        inOrder.verify(mockedManager).awaitTermination(anyInt(), any(), eq(Set.of("id-index")));
    }

    @Test
    public void test_run_pipeline_stops_when_a_stage_does_not_complete() throws Exception {
        TaskManager mockedManager = mock(TaskManager.class);
        when(mockedManager.startTask(eq(ScanTask.class), any(), any())).thenReturn("id-scan");
        Task<Long> failed = new Task<>(ScanTask.class.getName(), User.local(), new HashMap<>());
        failed.setError(new TaskError(new RuntimeException("boom")));
        doReturn(failed).when(mockedManager).getTask("id-scan");
        Properties properties = new Properties();
        properties.setProperty("stages", "SCAN,INDEX");

        CliApp.runPipeline(mockedManager, new PipelineHelper(new PropertiesProvider(properties)), properties);

        verify(mockedManager, never()).startTask(eq(IndexTask.class), any(), any());
    }

    private static Task<Long> doneTask() {
        Task<Long> task = new Task<>(ScanTask.class.getName(), User.local(), new HashMap<>());
        task.setResult(new TaskResult<>(0L));
        return task;
    }
```

Add these imports to the same file:

```java
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.tasks.IndexTask;
import org.mockito.InOrder;

import java.util.Properties;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
```

`mock(TaskManager.class)`, `org.icij.datashare.asynctasks.Task`, `TaskResult`, `User` and `ScanTask` are already imported by the existing test.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -pl datashare-app -am test -Dtest=CliAppTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol: method runPipeline(...)`.

- [ ] **Step 3: Add the barrier to CliApp**

In `CliApp.java`, replace the block from `PipelineHelper pipeline = new PipelineHelper(...)` (line 177) through `taskManager.shutdown();` (line 216) with:

```java
        PipelineHelper pipeline = new PipelineHelper(new PropertiesProvider(properties));
        logger.info("executing {}", pipeline);
        runPipeline(taskManager, pipeline, properties);
        taskManager.shutdown();
    }

    static final Map<Stage, Class<?>> TASK_CLASSES = Map.of(
            Stage.SCAN, ScanTask.class,
            Stage.SCANIDX, ScanIndexTask.class,
            Stage.DEDUPLICATE, DeduplicateTask.class,
            Stage.INDEX, IndexTask.class,
            Stage.ENQUEUEIDX, EnqueueFromIndexTask.class,
            Stage.CATEGORIZE, CategorizeTask.class,
            Stage.CREATENLPBATCHESFROMIDX, CreateNlpBatchesFromIndex.class,
            Stage.NLP, ExtractNlpTask.class,
            Stage.ARTIFACT, ArtifactTask.class);

    /**
     * Runs the configured stages one at a time, awaiting each stage's task before starting the next.
     * Consumers stop as soon as their input queue is empty (there is no end-of-stream sentinel), so
     * this barrier is what makes "empty" mean "the producer is done" for any number of workers.
     * PipelineHelper.stages is already sorted by Stage.comparator, which is pipeline order.
     * ponytail: launcher-side barrier, so a producer and its consumer stage no longer overlap. If
     * that overlap ever measures, gate the consumer on the upstream task state (pass the producer's
     * task id in the consumer's args) rather than reintroducing a sentinel.
     */
    static void runPipeline(TaskManager taskManager, PipelineHelper pipeline, Properties properties) throws Exception {
        for (Stage stage : pipeline.stages) {
            Class<?> taskClass = TASK_CLASSES.get(stage);
            if (taskClass == null) {
                logger.warn("no task class for stage {}, skipping it", stage);
                continue;
            }
            String taskId = taskManager.startTask(taskClass, nullUser(), propertiesToMap(properties));
            if (taskId == null) {
                logger.error("stage {} could not be started, stopping the pipeline", stage);
                return;
            }
            taskManager.awaitTermination(Integer.MAX_VALUE, SECONDS, Set.of(taskId));
            Task.State state = taskManager.getTask(taskId).getState();
            if (state != Task.State.DONE) {
                logger.error("stage {} ended in state {}, stopping the pipeline: a downstream stage would read its partial queue as complete", stage, state);
                return;
            }
        }
    }
```

Add to `CliApp.java` imports:

```java
import org.icij.datashare.asynctasks.Task;
```

`CreateNlpBatchesFromIndex` was previously started by class name string (line 205); `TASK_CLASSES` uses the class, which `startTask(Class, User, Map)` resolves the same way through its `@TaskGroup` annotation. If `HashSet` and `Set`-of-ids leftovers become unused, drop the now-dead `import java.util.HashSet;` only if nothing else in the file uses it.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -pl datashare-app -am test -Dtest=CliAppTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 3 tests.

- [ ] **Step 5: Warn when taskWorkers weakens the barrier**

In `CommonMode.java`, right after line 129 (`executorService = Executors.newFixedThreadPool(...)`), add:

```java
        if (getTaskWorkersNb() > 1) {
            logger.warn("taskWorkers is set to {}: pipeline stages started together may run concurrently. A consumer stage stops as soon as its input queue is empty, so it can exit before its producer stage finished enqueuing.", getTaskWorkersNb());
        }
```

- [ ] **Step 6: Verify the module still builds and its tests pass**

Run: `mvn -pl datashare-app -am test -Dtest='CliApp*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 7: Commit**

Use the `/commit` skill. Suggested message:

```
refactor(pipeline): sequence stage tasks in CliApp so a stage sees a complete queue
```

---

### Task 2: Drop PATH_POISON from the SCAN, DEDUPLICATE and INDEX chain

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/ScanTask.java:41-46`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/DeduplicateTask.java:48-63`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/IndexTask.java:77-95`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/ScanTaskTest.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/DeduplicateTaskTest.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/IndexTaskIntTest.java`

**Interfaces:**
- Consumes: the Task 1 guarantee that SCAN finishes before DEDUPLICATE or INDEX starts.
- Produces: `PipelineTask.PATH_POISON` keeps its current declaration (`public static Path PATH_POISON = Paths.get("POISON")`) but is now only ever compared against, never enqueued. No other task consumes anything from here.

- [ ] **Step 1: Write the failing tests**

In `ScanTaskTest.java`, change both size assertions:

```java
        assertThat(queue.size()).isEqualTo(3);
```

(one in `test_scan` for queue `extract:queue:index`, one in `test_scan_with_queue_name` for queue `foo:index`, dropping the `// with POISON` comment in both).

In `DeduplicateTaskTest.java`, remove every `.add(PATH_POISON)` and `.put(PATH_POISON)` seed and the trailing poison assertions, so the four tests read:

```java
    @Test(timeout = 2000)
    public void test_filter_empty() throws Exception {
        assertThat(new DeduplicateTask(docCollectionFactory, new Task<>(DeduplicateTask.class.getName(), User.local(), defaultOpts), null).call()).isEqualTo(0);
    }

    @Test(timeout = 2000)
    public void test_filter_queue_removes_duplicates() throws Exception {
        docCollectionFactory.createQueue("test:queue:deduplicate", Path.class).put(get("/path/to/doc"));
        docCollectionFactory.createQueue("test:queue:deduplicate", Path.class).put(get("/path/to/doc"));

        assertThat(new DeduplicateTask(docCollectionFactory,  new Task<>(DeduplicateTask.class.getName(), User.local(), defaultOpts), null).call()).isEqualTo(1);

        assertThat(docCollectionFactory.createQueue("test:queue:index", Path.class).size()).isEqualTo(1);
    }

    @Test(timeout = 2000)
    public void test_pipeline_task_transfer_to_output_queue() throws Exception {
        task.inputQueue.put(get("/path/to/doc1"));
        task.inputQueue.put(get("/path/to/doc2"));

        task.transferToOutputQueue();

        assertThat(task.inputQueue.isEmpty()).isTrue();
        DocumentQueue<Path> outputQueue = docCollectionFactory.createQueue(task.getOutputQueueName(), Path.class);
        assertThat(outputQueue.size()).isEqualTo(2);
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc1");
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc2");
    }

    @Test(timeout = 2000)
    public void test_pipeline_task_conditional_transfer_to_output_queue() throws Exception {
        task.inputQueue.put(get("/path/to/doc1"));
        task.inputQueue.put(get("/path/to/doc2"));

        task.transferToOutputQueue(p -> p.toString().contains("1"));

        assertThat(task.inputQueue.isEmpty()).isTrue();
        DocumentQueue<Path> outputQueue = docCollectionFactory.createQueue(task.getOutputQueueName(), Path.class);
        assertThat(outputQueue.size()).isEqualTo(1);
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc1");
    }
```

Also remove the now-unused `import static org.icij.datashare.tasks.PipelineTask.PATH_POISON;` from that file.

In `IndexTaskIntTest.java`, add the retro-compat test:

```java
    @Test
    public void index_task_skips_a_legacy_poison_entry_at_the_head_of_the_queue() throws Exception {
        DocumentQueue<Path> queue = inputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getQueueNameFor(Stage.INDEX), Path.class);
        // a sentinel written by a pre-21.16 run sits FIRST: the drain must skip it and keep going,
        // where the old drain(PATH_POISON) would have stopped here and indexed nothing
        queue.add(PipelineTask.PATH_POISON);
        queue.add(Paths.get(ClassLoader.getSystemResource("docs/doc.txt").getPath()));

        new IndexTask(spewer, inputQueueFactory, new Task<>(IndexTask.class.getName(), User.local(), map), null).call();

        DocumentQueue<String> outputQueue = outputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getOutputQueueNameFor(Stage.INDEX), String.class);
        assertThat(outputQueue).contains("bc6852541ef5200206a7a9740f3d2d62178a1f53b1aa5417ab426c6ec1f7cbc7");
        assertThat(logback.logs(Level.WARN)).contains("skipping legacy POISON entry in queue test:queue:index");
    }
```

Add to that file:

```java
import org.icij.datashare.test.LogbackCapturingRule;
import org.slf4j.event.Level;
```

and the rule next to the existing `@Rule public ElasticsearchRule es`:

```java
    @Rule public LogbackCapturingRule logback = new LogbackCapturingRule();
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -pl datashare-app -am test -Dtest='ScanTaskTest,DeduplicateTaskTest,IndexTaskIntTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL. `ScanTaskTest` expected 3 but was 4; `DeduplicateTaskTest.test_filter_empty` times out at 2000 ms because `take()` blocks with no sentinel; `IndexTaskIntTest.index_task_skips_a_legacy_poison_entry_at_the_head_of_the_queue` finds an empty output queue because the drain stops at the sentinel.

- [ ] **Step 3: Stop emitting PATH_POISON in ScanTask**

In `ScanTask.java`, replace `call()`:

```java
    @Override
    public Long call() throws Exception {
        super.call();
        ScannerVisitor scannerVisitor = scanner.createScannerVisitor(path);
        return scannerVisitor.call();
    }
```

- [ ] **Step 4: Drain until empty in DeduplicateTask**

In `DeduplicateTask.java`, replace `transferToOutputQueue(Predicate<Path> filter)`:

```java
    long transferToOutputQueue(Predicate<Path> filter) throws Exception {
        long originalSize = inputQueue.size();
        try (DocumentQueue<Path> outputQueue = factory.createQueue(getOutputQueueName(), Path.class)) {
            Path path;
            while ((path = inputQueue.poll()) != null) {
                if (filter.test(path)) {
                    outputQueue.add(path);
                }
            }
            return originalSize - outputQueue.size();
        }
    }
```

- [ ] **Step 5: Drain without a sentinel in IndexTask and skip a legacy one**

In `IndexTask.java`, replace the `progressTrackConsumer` assignment (line 77) with:

```java
        progressTrackConsumer = path -> {
            // ponytail: transitional. Redis queue keys survive upgrades, so a pre-21.16 run can
            // leave a "POISON" path in this queue. Skip it instead of trying to extract a file
            // named POISON. Remove once no live queue predates 21.16.
            if (PATH_POISON.equals(path)) {
                logger.warn("skipping legacy POISON entry in queue {}", inputQueue.getName());
                return;
            }
            consumer.accept(path);
            processed.incrementAndGet();
            if (progressCallback != null) {
                progressCallback.apply(getProgressRate());
            }
        };
```

and line 95 with:

```java
            totalToProcess = drainer.drain().get();
```

`drain()` with no argument runs the same loop and stops on a null poll, because `queuePoll` is never configured so the drainer's poll timeout is `Duration.ZERO`. It counts the skipped sentinel, so a legacy queue reports one extra unit in the progress denominator. That is accepted.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `mvn -pl datashare-app -am test -Dtest='ScanTaskTest,DeduplicateTaskTest,IndexTaskIntTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. `IndexTaskIntTest.index_task_should_enqueue_poison_pill` still passes unchanged: its trailing sentinel comes from `ElasticsearchSpewer.close()`, which Task 4 removes.

- [ ] **Step 7: Commit**

Use the `/commit` skill. Suggested message:

```
refactor(pipeline): stop the scan and index chain on an empty queue instead of a sentinel
```

---

### Task 3: Drop STRING_POISON from the ENQUEUEIDX and ARTIFACT chain

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/EnqueueFromIndexTask.java:88-104`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/ArtifactTask.java:60-230`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/EnqueueFromIndexTaskTest.java:87-106`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactTaskTest.java`

**Interfaces:**
- Consumes: the Task 1 guarantee that ENQUEUEIDX finishes before ARTIFACT starts.
- Produces: `ArtifactTask` no longer references `ExtractNlpTask.NB_MAX_POLLS`, which is what lets Task 4 delete that constant. `ArtifactTask.drainStaleResidualPoison()` is gone.

- [ ] **Step 1: Write the failing tests**

In `EnqueueFromIndexTaskTest.java`, in `test_no_query_targeting_artifact_ignores_nlp_pipeline_filter`, replace the two assertions and their comment with:

```java
        assertThat(factory.queues.get("test:queue:artifact")).hasSize(5);
```

In `ArtifactTaskTest.java`, delete these five tests outright, including their comment blocks:

- `test_terminates_on_poison_instead_of_polling_timeout`
- `test_poison_is_not_processed_as_a_document`
- `test_poison_propagates_to_every_worker`
- `test_doc_stranded_behind_stale_poison_is_recovered_by_next_run`
- `test_transient_null_poll_does_not_drop_a_later_doc`

The last one encodes the contract being removed on purpose: with the launcher barrier there is no concurrent producer, so a null poll means done and must not be retried.

Rewrite the two-runs test without sentinels:

```java
    @Test(timeout = 10000)
    public void test_two_sequential_runs_on_same_queue_both_process_docs() throws Exception {
        // the ARTIFACT input queue name is static (extract:queue:artifact) and is never deleted
        // between runs, so a re-run reuses it. Nothing is left behind by run 1 to terminate run 2's
        // workers early.
        indexEmbeddedDoc();
        String secondId = "1111111111111111111111111111111111111111111111111111111111111111";
        mockIndexer.indexFile("prj", secondId,
                Path.of(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).toURI()),
                "message/rfc822");

        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_DOC_SHA256);

        Long firstRun = runArtifactTask();
        assertThat(firstRun).isEqualTo(1);

        queue.add(secondId);

        Long secondRun = runArtifactTask();
        assertThat(secondRun).isEqualTo(1);
    }
```

Add the retro-compat test:

```java
    @Test(timeout = 10000)
    public void test_legacy_poison_entry_is_skipped_and_the_doc_behind_it_is_processed() throws Exception {
        // a sentinel written by a pre-21.16 run needs no dedicated code here: it resolves to a doc
        // reference with id POISON, the indexer returns null for it, and PipelineTask.warnIfNull
        // already logs and skips. The doc behind it must still be processed.
        indexEmbeddedDoc();
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add("POISON");
        queue.add(EMBEDDED_DOC_SHA256);

        Long numberOfDocuments = runArtifactTask();

        assertThat(numberOfDocuments).isEqualTo(1);
        assertThat(logback.logs(Level.WARN)).contains("document <POISON> could not be retrieved from index prj (missing document or index fetch error), skipping");
    }
```

Remove `import org.icij.extract.queue.MemoryDocumentQueue;`, `import java.util.concurrent.TimeUnit;` and `import java.util.concurrent.atomic.AtomicInteger;` if the deletions above leave them unused.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -pl datashare-app -am test -Dtest='EnqueueFromIndexTaskTest,ArtifactTaskTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL. `EnqueueFromIndexTaskTest` expected size 5 but was 6; `test_legacy_poison_entry_is_skipped_and_the_doc_behind_it_is_processed` fails because the startup drain strips the entry and the worker relay treats it as a stop signal, so the WARN is never logged.

- [ ] **Step 3: Stop emitting STRING_POISON in EnqueueFromIndexTask**

In `EnqueueFromIndexTask.java`, delete the whole `if (nextStage == Stage.ARTIFACT) { ... }` block (lines 92 to 100, comment included), leaving:

```java
        try (DocumentQueue<String> outputQueue = factory.createQueue(getOutputQueueName(), String.class)) {
            do {
                docsToProcess.forEach(doc -> outputQueue.add(DocReference.fromDocument((Document) doc).toQueueEntry()));
                docsToProcess = searcher.scroll(scrollDuration).toList();
            } while (!docsToProcess.isEmpty());
            searcher.clearScroll();
            logger.info("enqueued into {} {} files", outputQueue.getName(), totalHits);
        }
```

Remove the `import static org.icij.datashare.tasks.PipelineTask.STRING_POISON;`-style reference (the class references `STRING_POISON` inherited from `PipelineTask`, so only the usage disappears).

- [ ] **Step 4: Reduce the ArtifactTask worker to a drain**

In `ArtifactTask.java`:

Delete the `pollingInterval` field, its constructor assignment, and the `POLLING_INTERVAL_SECONDS_OPT` / `DEFAULT_POLLING_INTERVAL_SEC` imports. Replace the opening log line in `call()`:

```java
        logger.info("creating artifact cache in {} for project {} from queue {} with {} worker(s)", artifactDir, project, inputQueue.getName(), parallelism);
```

Delete the `drainStaleResidualPoison();` call and the whole `drainStaleResidualPoison()` method with its comment block (lines 194 to 230), plus the comment block above the call (lines 86 to 93).

Replace the loop in `runWorker` (the `int nbMaxPolls = ...` line through the end of the `while` body's closing brace, and the surrounding `try`/`catch (InterruptedException e)`) with:

```java
        String queueEntry;
        // The launcher awaits ENQUEUEIDX before starting this stage, so an empty queue means the
        // producer is done: a non-blocking poll returning null is the terminal condition, for one
        // worker or for N. The interrupt check keeps cancellation prompt, since cancel() calls
        // executor.shutdownNow() while a worker may be between two polls.
        while (!Thread.currentThread().isInterrupted() && (queueEntry = inputQueue.poll()) != null) {
            try {
                Document doc = getDocument(indexer, project.name, DocReference.parse(queueEntry), SOURCE_EXCLUDES);
                if (doc == null) {
                    nbSkipped.incrementAndGet();
                    continue;
                }
                // Each polled node is produced into its own content-addressed directory.
                Path docArtifactDir = ArtifactPath.dir(projectRoot, doc.getId());
                if (producer.run(selected, new ArtifactContext(project, doc, docArtifactDir, extractor), force)) {
                    nbDocs.incrementAndGet();
                } else {
                    nbFailed.incrementAndGet();
                }
            } catch (Throwable e) {
                logger.error("error in ArtifactTask loop", e);
                nbFailed.incrementAndGet();
            }
        }
```

There is no outer `try` any more: the worker leaves nothing in the queue to clean up, and the inner `try`/`catch (Throwable)` already contains per-doc failures. The old `catch (InterruptedException e)` must go, because a non-blocking `poll()` does not throw it and javac rejects catching a checked exception that cannot be thrown. `runWorker` keeps its current signature and stays `void`. Remove the `import java.util.concurrent.TimeUnit;` if unused.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -pl datashare-app -am test -Dtest='EnqueueFromIndexTaskTest,ArtifactTaskTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. The remaining `ArtifactTaskTest` cases still pass `pollingInterval` in their properties; that key is now ignored, which is harmless.

- [ ] **Step 6: Commit**

Use the `/commit` skill. Suggested message:

```
refactor(artifact): drain the artifact queue until empty and delete the poison relay
```

---

### Task 4: Delete STRING_POISON from the CATEGORIZE and NLP chain

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/CategorizeTask.java:42-92`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/ExtractNlpTask.java:46-100`
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchSpewer.java:418-421`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/PipelineTask.java:31`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/CategorizeTaskIntTest.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/ExtractNlpTaskIntTest.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/IndexTaskIntTest.java`

**Interfaces:**
- Consumes: `ArtifactTask` no longer referencing `ExtractNlpTask.NB_MAX_POLLS` (Task 3).
- Produces: `PipelineTask.STRING_POISON` no longer exists. `PipelineTask.PATH_POISON` stays.

- [ ] **Step 1: Write the failing tests**

In `CategorizeTaskIntTest.java`:

Rename the helpers to drop the sentinel and delete `enqueuePoison`:

```java
    private void indexAndEnqueue(String queueName, Document... documents) {
        Arrays.stream(documents).forEach(this::index);
        enqueue(queueName, documents);
    }

    private void indexAndEnqueue(Document... documents) {
        indexAndEnqueue(DEFAULT_INPUT_QUEUE_NAME, documents);
    }

    private void indexAndEnqueueTwoDocuments() {
        indexAndEnqueueTwoDocuments(DEFAULT_INPUT_QUEUE_NAME);
    }

    private void indexAndEnqueueTwoDocuments(String queueName) {
        indexAndEnqueue(queueName,
                aDocWithContentType("application/pdf"),
                aDocWithContentType("notOkContentType"));
    }

    private void enqueue(String queueName, Document... documents){
        DocumentQueue<String> queue = documentCollectionFactory.createQueue(queueName, String.class);
        Arrays.stream(documents).forEach(d -> queue.add(d.getId()));
    }

    private void enqueue(Document... documents){
        enqueue(DEFAULT_INPUT_QUEUE_NAME, documents);
    }
```

Update the call sites: `indexAndEnqueueWithPoison(videoDoc, pdfDoc, unknownTypeDoc)` becomes `indexAndEnqueue(videoDoc, pdfDoc, unknownTypeDoc)`, and `enqueueWithPoison(aDoc())` becomes `enqueue(aDoc())`.

Update the output-queue sizes: `isEqualTo(4)` becomes `isEqualTo(3)` in `test_categorization`, `isEqualTo(3)` becomes `isEqualTo(2)` in `test_categorization_with_different_queue_name`, `isEqualTo(2)` becomes `isEqualTo(1)` in `test_receiving_null_from_indexer`. Drop the `// with POISON` comments.

Delete the three `documentCollectionFactory.createQueue("extract:queue:categorize", String.class).add(STRING_POISON);` lines in the ENQUEUEIDX integration tests, and the `import static org.icij.datashare.tasks.PipelineTask.STRING_POISON;`.

In `ExtractNlpTaskIntTest.java`, delete both `queue.add(PipelineTask.STRING_POISON);` lines.

Two progress assertions also break, because `getProgressRate()` is `done/(done + queue.size())` and the sentinel inflated the denominator by one. Both were pinning artifacts. With the sentinel gone, progress reaches exactly 1.0 when the queue drains, which is exactly representable and is a real property of the new design, so assert that instead:

- `CategorizeTaskIntTest.test_update_progress`: replace `assertThat(progressValues.get(0)).isLessThan(0.5); //There are 3 values` (2 docs now give [0.5, 1.0], not [0.333, 0.667]) with:

```java
        assertThat(progressValues.get(0)).isLessThan(progressValues.get(progressValues.size() - 1));
        assertThat(progressValues).contains(1.0);
```

- `ExtractNlpTaskIntTest.test_progress`: replace `assertThat(progressValues).contains(0.5);` (3 docs now give [0.333, 0.667, 1.0], never exactly 0.5) with `assertThat(progressValues).contains(1.0);`, keeping the size and monotonicity assertions above it.

In `ExtractNlpTaskTest.java` (note: NOT the `IntTest`; this file was missed by the POISON-based file inventory because it names `NB_MAX_POLLS` instead), delete `test_exit_after_nb_max_attempts` and its now-unused `NB_MAX_POLLS` and `POLLING_INTERVAL_SECONDS_OPT` imports. Its premise, "the loop waits out a poll budget before exiting", is the behaviour being removed. Replace it with a test of the new contract, which the old code would fail by timing out:

```java
    @Test(timeout = 2000)
    public void test_returns_immediately_when_queue_is_empty() throws Exception {
        // The launcher awaits the producer stage before NLP starts, so an empty queue means done.
        // Deliberately no polling-interval option: the old bounded null-poll budget would have
        // waited out several default polling intervals here and blown this timeout.
        ExtractNlpTask nlpTask = new ExtractNlpTask(indexer, pipeline, factory,
                new Task<>(ExtractNlpTask.class.getName(), User.local(), Map.of()), null);

        assertThat(nlpTask.call()).isEqualTo(0L);
    }
```

In `IndexTaskIntTest.java`, rewrite the first test as:

```java
    @Test
    public void index_task_should_enqueue_indexed_doc_ids() throws Exception {
        DocumentQueue<Path> queue = inputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getQueueNameFor(Stage.INDEX), Path.class);
        queue.add(Paths.get(ClassLoader.getSystemResource("docs/doc.txt").getPath()));

        Long nbDocs = new IndexTask(spewer, inputQueueFactory, new Task<>(IndexTask.class.getName(), User.local(), map), null).call();

        assertThat(nbDocs).isEqualTo(1);
        DocumentQueue<String> outputQueue = outputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getOutputQueueNameFor(Stage.INDEX), String.class);
        assertThat(outputQueue).hasSize(1);
        assertThat(outputQueue.poll()).isEqualTo("bc6852541ef5200206a7a9740f3d2d62178a1f53b1aa5417ab426c6ec1f7cbc7");
    }
```

and remove `import static org.icij.datashare.tasks.PipelineTask.STRING_POISON;`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -pl datashare-app -am test -Dtest='CategorizeTaskIntTest,ExtractNlpTaskIntTest,IndexTaskIntTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL. `CategorizeTaskIntTest` and `ExtractNlpTaskIntTest` hang until their timeouts or return 0 messages, because with no sentinel the loops now wait out their empty-poll budgets; `IndexTaskIntTest` expected size 1 but was 2.

- [ ] **Step 3: Drain until empty in CategorizeTask**

In `CategorizeTask.java`, delete the `NB_MAX_POLLS` and `POLLING_INTERVAL_SECONDS` constants and the `java.util.concurrent.TimeUnit` import, then replace the body of `call()` after the opening `logger.info(...)` with:

```java
        String queueEntry;
        long nbMessages = 0;

        while ((queueEntry = inputQueue.poll()) != null) {
            try {
                Document retrievedFromIndexer = getDocument(indexer, project.getName(), DocReference.parse(queueEntry));
                if (retrievedFromIndexer != null) {
                    enrichWithType(retrievedFromIndexer);
                }
                nbMessages++;
                processed.incrementAndGet();
                progressCallback.apply(getProgressRate());
                if (!outputQueue.offer(queueEntry)) {
                    logger.warn("unable to offer {} to queue {}", queueEntry, outputQueue.getName());
                }
            } catch (Exception e) {
                logger.error("error in CategorizeTask loop", e);
            }
        }
        logger.info("exiting CategorizeTask loop after {} messages.", nbMessages);
        return nbMessages;
```

A legacy `"POISON"` entry is skipped by `warnIfNull` and then forwarded to the NLP queue, where `ExtractNlpTask` skips it the same way. That is fine and needs no guard.

- [ ] **Step 4: Drain until empty in ExtractNlpTask**

In `ExtractNlpTask.java`, delete the `NB_MAX_POLLS` constant, the `pollingIntervalSeconds` field and its constructor assignment, the `POLLING_INTERVAL_SECONDS_OPT` and `DEFAULT_POLLING_INTERVAL_SEC` imports and the `java.util.concurrent.TimeUnit` import, then replace the loop in `call()`:

```java
        String queueEntry;
        long nbMessages = 0;
        while ((queueEntry = inputQueue.poll()) != null) {
            try {
                findNamedEntities(project, queueEntry);
                nbMessages++;
                processed.incrementAndGet();
                progressCallback.apply(getProgressRate());
            } catch (Throwable e) {
                logger.error("error in ExtractNlpTask loop", e);
            }
        }
        logger.info("exiting ExtractNlpTask loop after {} messages.", nbMessages);
        return nbMessages;
```

- [ ] **Step 5: Stop emitting the sentinel from the spewer**

In `ElasticsearchSpewer.java`, delete the whole override:

```java
    @Override
    public void close() throws Exception {
        outputQueue.put("POISON");
    }
```

The parent `org.icij.spewer.Spewer.close()` is an empty method, so removing the override changes nothing else. This is the fifth emitter and the one that fed the NLP queue: `DocumentConsumer.awaitTermination` calls `spewer.close()` on every await iteration, so a long index run could enqueue several sentinels.

- [ ] **Step 6: Delete the constant**

In `PipelineTask.java`, delete line 31:

```java
    public static String STRING_POISON = "POISON";
```

Keep `PATH_POISON` (Task 2's `IndexTask` guard compares against it).

- [ ] **Step 7: Run the tests to verify they pass**

Run: `mvn -pl datashare-app -am test -Dtest='CategorizeTaskIntTest,ExtractNlpTaskIntTest,IndexTaskIntTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 8: Verify nothing still references the sentinel**

Run: `grep -rn "POISON" --include="*.java" datashare-app/src datashare-index/src datashare-tasks/src`
Expected: only `PipelineTask.PATH_POISON`, the `IndexTask` guard and its comment, the two retro-compat tests, and `DeduplicateTaskTest` if it still imports nothing. No emit site anywhere.

- [ ] **Step 9: Run the full app and index module test suites**

Run: `mvn -pl datashare-app,datashare-index -am test`
Expected: PASS.

- [ ] **Step 10: Commit**

Use the `/commit` skill. Suggested message:

```
refactor(pipeline): drain the categorize and nlp queues until empty, delete STRING_POISON
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
| --- | --- |
| Deletions table, ScanTask / DeduplicateTask / IndexTask | Task 2 |
| Deletions table, ArtifactTask relay, startup drain, null-poll budget | Task 3 |
| Deletions table, ExtractNlpTask / CategorizeTask loops, NB_MAX_POLLS | Task 4 |
| extract-lib untouched | Global constraints, plus Task 2 Step 5 using `drain()` |
| Launcher barrier, stage order from the enum, failed stage stops the pipeline | Task 1 |
| Retro compatibility, IndexTask guard | Task 2 Steps 1 and 5 |
| Retro compatibility, string queues need no code | Task 3 Step 1 test, Task 4 Step 3 note |
| Web endpoints, `taskWorkers > 1` warning | Task 1 Step 5 |
| Test updates and deletions | Tasks 2, 3, 4 |
| Two new tests | Task 1 Step 1, Task 2 Step 1 |

Gap found and closed while planning: the spec's deletion table missed `ElasticsearchSpewer.close()`, a fifth emitter in `datashare-index` that puts `"POISON"` on the NLP queue. Covered in Task 4 Step 5. Also newly covered, not in the spec: `ArtifactTask` and the two NLP-family tasks stop reading `pollingInterval`, since with a complete queue there is nothing to wait for. The CLI option stays declared, so no settings file breaks.

**Placeholder scan:** none. Every code step carries the code to write, every test step the assertion and the exact command.

**Type consistency:** `runPipeline(TaskManager, PipelineHelper, Properties)` and `TASK_CLASSES` are named identically in Task 1's implementation, its tests, and the interfaces blocks. `PATH_POISON` keeps its existing type `Path` and is used only in `IndexTask` and tests. `isLegacyPoison` from an earlier spec draft appears nowhere: the guard is inline in `IndexTask`.
