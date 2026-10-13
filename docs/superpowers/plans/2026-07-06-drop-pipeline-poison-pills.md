# Drop POISON pills from pipeline queues — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the POISON-pill end-of-stream signal from the datashare pipeline queues, terminate each draining stage on a quiet queue via the poll timeout, and silently skip any legacy POISON entries still present in production queues.

**Architecture:** Producers stop enqueuing POISON. Draining stages (INDEX, DEDUPLICATE) terminate when the queue stays empty for a configurable `queuePoll` window (default 120s), and polling stages (NLP, CATEGORIZE) terminate on their existing `NB_MAX_POLLS` empty-poll budget. Every consumer treats a POISON entry as a skip-and-continue (logged at debug), never a terminator or a document. extract-lib is untouched: `IndexTask` just calls `drain()` instead of `drain(POISON)`.

**Tech Stack:** Java 17, Maven multi-module (`datashare-cli`, `datashare-api`, `datashare-index`, `datashare-app`), JUnit 4 + Mockito + fest-assert, extract-lib (`org.icij.extract.queue.*`, `org.icij.time.HumanDuration`), Redis/Elasticsearch for integration tests.

## Global Constraints

- Do **not** modify extract-lib. `DocumentQueueDrainer.drain(Path poison)` and `drain()` both stay; datashare only changes which it calls. If an extract-lib change ever seems needed, stop and ask.
- No removed or renamed CLI options; no renamed queues. `queuePoll` reuses the existing option key.
- Default `queuePoll` for pipeline draining is exactly `"120s"` (constant `DEFAULT_QUEUE_POLL`). An explicitly provided `queuePoll` (including `0`) is honored — this is a plain positive default, not a hard floor.
- Legacy POISON entries are skipped **silently at debug level**, never indexed as a file named `POISON`, never crash, never stop consumption.
- POISON constants `PipelineTask.PATH_POISON` (`Paths.get("POISON")`) and `PipelineTask.STRING_POISON` (`"POISON"`) stay (tests and legacy-entry recognition depend on them).
- The drainer only honors **whole-second** poll timeouts (`pollTimeout.getSeconds() > 0`); sub-second values fall back to non-blocking. Tests that must observe INDEX blocking use `queuePoll` >= `"1s"`. `DeduplicateTask` polls the queue directly (millisecond granularity), so its tests may use `"10ms"`.
- TDD: red before green for every behavior. One semantic commit per task (via the `/commit` skill; do not push). The design doc under `docs/superpowers/` must NOT be committed.
- Single-test command (per project convention): `mvn -pl <module> -am test -Dtest=<Class[#method]> -Dsurefire.failIfNoSpecifiedTests=false`. Integration tests need Elasticsearch at `elasticsearch:9200` and Redis at `redis:6379`.
- Known pre-existing failures to ignore in this devenv: `ExtensionLoaderTest#test_load_jars`, `TaskManagersIntTest` (needs RabbitMQ), `TaskManagerAmqpTest#test_task_result_uri_result_type` (order-dependent flake, fails on main too).

## Poison surface (verified in this codebase)

Producers to remove:
- `ScanTask.java:44` — `outputQueue.add(PATH_POISON)` (SCAN -> index queue).
- `DeduplicateTask.java:59` — `outputQueue.add(PATH_POISON)` (DEDUPLICATE -> index queue).
- `CategorizeTask.java:89-91` — `outputQueue.offer(STRING_POISON)` (CATEGORIZE -> nlp queue).
- `datashare-index` `ElasticsearchSpewer.java:320` — `outputQueue.put("POISON")` in `close()` (INDEX -> nlp queue).

Consumers to change (terminate on quiet queue + skip legacy poison):
- `IndexTask.java:92` — `drainer.drain(PATH_POISON)` -> `drainer.drain()`; skip poison in consumer; default `queuePoll` 120s.
- `DeduplicateTask.java:54` — `inputQueue.take()`-until-POISON -> `poll(queuePoll)`-until-null; skip poison.
- `ExtractNlpTask.java:79` — stop terminating on `STRING_POISON`; skip-and-continue; keep `NB_MAX_POLLS` timeout (already configurable via `pollingInterval`).
- `CategorizeTask.java:65` — same skip-and-continue; make `pollingInterval` configurable (currently hardcoded 60s) so termination works and tests stay fast.

## File Structure

- `datashare-cli/.../DatashareCliOptions.java` — add `QUEUE_POLL_OPT`, `DEFAULT_QUEUE_POLL`, `queuePoll(parser)` help. (constants + CLI help, one responsibility)
- `datashare-cli/.../DatashareCli.java` — register `queuePoll(parser)`.
- `datashare-app/.../tasks/PipelineTask.java` — shared `isPoison(...)` + `pipelineQueuePoll(...)` helpers.
- `datashare-app/.../tasks/ScanTask.java` — stop producing poison.
- `datashare-index/.../elasticsearch/ElasticsearchSpewer.java` — stop producing poison on close.
- `datashare-app/.../tasks/IndexTask.java` — quiet-queue drain + skip poison + default queuePoll.
- `datashare-app/.../tasks/DeduplicateTask.java` — poll-timeout drain + skip poison + no forwarded poison.
- `datashare-app/.../tasks/ExtractNlpTask.java` — skip poison, timeout-only termination.
- `datashare-app/.../tasks/CategorizeTask.java` — configurable poll interval, skip poison, no forwarded poison.
- Tests updated/added alongside each of the above.

---

### Task 1: Shared poison + queue-poll helpers and CLI option help

**Files:**
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCliOptions.java`
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCli.java`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/PipelineTask.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/PipelineTaskTest.java` (create)

**Interfaces:**
- Produces:
  - `DatashareCliOptions.QUEUE_POLL_OPT` = `"queuePoll"` (String constant)
  - `DatashareCliOptions.DEFAULT_QUEUE_POLL` = `"120s"` (String constant)
  - `DatashareCliOptions.queuePoll(OptionParser parser)` (void)
  - `PipelineTask.isPoison(Path path)` -> boolean (static)
  - `PipelineTask.isPoison(String id)` -> boolean (static)
  - `PipelineTask.pipelineQueuePoll(PropertiesProvider propertiesProvider)` -> `java.time.Duration` (static; reads `queuePoll`, default `"120s"`)

- [ ] **Step 1: Write the failing test**

Create `datashare-app/src/test/java/org/icij/datashare/tasks/PipelineTaskTest.java`:

```java
package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class PipelineTaskTest {
    @Test
    public void test_isPoison_path() {
        assertThat(PipelineTask.isPoison(Paths.get("POISON"))).isTrue();
        assertThat(PipelineTask.isPoison(Paths.get("/real/doc.txt"))).isFalse();
        assertThat(PipelineTask.isPoison((Path) null)).isFalse();
    }

    @Test
    public void test_isPoison_string() {
        assertThat(PipelineTask.isPoison("POISON")).isTrue();
        assertThat(PipelineTask.isPoison("realId")).isFalse();
        assertThat(PipelineTask.isPoison((String) null)).isFalse();
    }

    @Test
    public void test_pipeline_queue_poll_defaults_to_120s() {
        assertThat(PipelineTask.pipelineQueuePoll(new PropertiesProvider()))
                .isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    public void test_pipeline_queue_poll_honors_explicit_value() {
        PropertiesProvider pp = new PropertiesProvider(Map.of("queuePoll", "5s"));
        assertThat(PipelineTask.pipelineQueuePoll(pp)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    public void test_pipeline_queue_poll_honors_explicit_zero() {
        PropertiesProvider pp = new PropertiesProvider(Map.of("queuePoll", "0"));
        assertThat(PipelineTask.pipelineQueuePoll(pp)).isEqualTo(Duration.ZERO);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl datashare-app -am test -Dtest=PipelineTaskTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILE FAILURE — `isPoison`/`pipelineQueuePoll` not defined.

- [ ] **Step 3: Add the CLI constants and help method**

In `DatashareCliOptions.java`, add constants next to the other option keys (near `SEARCH_QUERY_OPT`, ~line 146) and defaults (near `DEFAULT_POLLING_INTERVAL_SEC`, ~line 263):

```java
    public static final String QUEUE_POLL_OPT = "queuePoll";
```
```java
    public static final String DEFAULT_QUEUE_POLL = "120s";
```

Add the help method next to `pollingInterval(...)` (~line 1044):

```java
    public static void queuePoll(OptionParser parser) {
        parser.acceptsAll(singletonList(QUEUE_POLL_OPT),
                        "Time INDEX/DEDUPLICATE wait for new documents on the shared queue before " +
                        "concluding it is drained (e.g. \"120s\"). Raise it above your worst-case gap " +
                        "between SCAN producing and INDEX consuming when running SCAN and INDEX on " +
                        "separate instances. Defaults to " + DEFAULT_QUEUE_POLL + " for pipeline draining.")
                .withRequiredArg()
                .ofType(String.class);
    }
```

Note: no `defaultsTo(...)` — the default value lives in `DEFAULT_QUEUE_POLL` and is applied by the task layer (`pipelineQueuePoll`), so tasks constructed directly (not via CLI) still get 120s.

- [ ] **Step 4: Register the option in the parser**

In `DatashareCli.java`, next to `DatashareCliOptions.pollingInterval(parser);` (~line 209):

```java
        DatashareCliOptions.queuePoll(parser);
```

- [ ] **Step 5: Add the shared helpers to PipelineTask**

In `PipelineTask.java`, add imports:

```java
import org.icij.time.HumanDuration;
import java.time.Duration;

import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_QUEUE_POLL;
import static org.icij.datashare.cli.DatashareCliOptions.QUEUE_POLL_OPT;
```

Add the helpers (below the `PATH_POISON`/`STRING_POISON` fields):

```java
    public static boolean isPoison(Path path) {
        return PATH_POISON.equals(path);
    }

    public static boolean isPoison(String id) {
        return STRING_POISON.equals(id);
    }

    public static Duration pipelineQueuePoll(PropertiesProvider propertiesProvider) {
        return HumanDuration.parse(propertiesProvider.get(QUEUE_POLL_OPT).orElse(DEFAULT_QUEUE_POLL));
    }
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -pl datashare-app -am test -Dtest=PipelineTaskTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (5 tests).

- [ ] **Step 7: Commit**

```bash
git add datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCliOptions.java \
        datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCli.java \
        datashare-app/src/main/java/org/icij/datashare/tasks/PipelineTask.java \
        datashare-app/src/test/java/org/icij/datashare/tasks/PipelineTaskTest.java
git commit -m "feat(tasks): add poison-skip and queuePoll helpers with CLI help"
```

---

### Task 2: ScanTask stops producing POISON

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/ScanTask.java:44`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/ScanTaskTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: SCAN output queue now contains exactly the scanned paths (no trailing POISON).

- [ ] **Step 1: Update the tests to expect no POISON (failing)**

In `ScanTaskTest.java`, change both size assertions from `4` to `3` and update the comments:

```java
    public void test_scan() throws Exception {
        assertThat(new ScanTask(documentCollectionFactory, new Task<>("org.icij.datashare.tasks.ScanTask", User.local(),
                Map.of(DATA_DIR_OPT, Paths.get(ClassLoader.getSystemResource("docs").getPath()).toString())), null).call()).isEqualTo(3);
        DocumentQueue<Path> queue = documentCollectionFactory.createQueue("extract:queue:index", Path.class);
        assertThat(queue.size()).isEqualTo(3); // no POISON
    }

    public void test_scan_with_queue_name() throws Exception {
        assertThat(new ScanTask(documentCollectionFactory, new Task<>("org.icij.datashare.tasks.ScanTask", User.local(),
                Map.of(DATA_DIR_OPT, Paths.get(ClassLoader.getSystemResource("docs").getPath()).toString(),
                        QUEUE_NAME_OPT, "foo")), null).call()).isEqualTo(3);
        DocumentQueue<Path> queue = documentCollectionFactory.createQueue("foo:index", Path.class);
        assertThat(queue.size()).isEqualTo(3); // no POISON
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl datashare-app -am test -Dtest=ScanTaskTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — actual size 4 (POISON still added), expected 3.

- [ ] **Step 3: Remove the POISON production**

In `ScanTask.java`, delete line 44 so `call()` becomes:

```java
    @Override
    public Long call() throws Exception {
        super.call();
        ScannerVisitor scannerVisitor = scanner.createScannerVisitor(path);
        Long scanned = scannerVisitor.call();
        return scanned;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl datashare-app -am test -Dtest=ScanTaskTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tasks/ScanTask.java \
        datashare-app/src/test/java/org/icij/datashare/tasks/ScanTaskTest.java
git commit -m "feat(tasks): stop enqueuing POISON when scan ends"
```

---

### Task 3: IndexTask drains a quiet queue, skips legacy POISON; spewer stops producing POISON

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/IndexTask.java`
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchSpewer.java:318-321`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/IndexTaskIntTest.java`

**Interfaces:**
- Consumes: `PipelineTask.isPoison(Path)`, `PipelineTask.pipelineQueuePoll(PropertiesProvider)` (Task 1).
- Produces: INDEX drains until the queue is silent for `queuePoll` (default 120s); POISON entries in the input are skipped (never indexed, never sent to the NLP output queue). The INDEX -> NLP output queue contains only indexed document ids (no POISON).

- [ ] **Step 1: Write the failing integration tests**

`IndexTaskIntTest` runs against Elasticsearch. Add `queuePoll` to the shared `map` (so the pre-populated tests drain and stop fast instead of waiting 120s), replace the poison-pill test, and add poison-skip + quiet-queue tests.

Change the shared `map` field:

```java
    private Map<String, Object> map = new HashMap<>() {{
        put("defaultProject", es.getIndexName());
        put("queueName", "test:queue");
        put("queuePoll", "0"); // pre-populated queue: drain then stop immediately
    }};
```

Replace `index_task_should_enqueue_poison_pill` with:

```java
    @Test
    public void index_task_forwards_only_document_ids_without_poison() throws Exception {
        DocumentQueue<Path> queue = inputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getQueueNameFor(Stage.INDEX), Path.class);
        queue.add(Paths.get(ClassLoader.getSystemResource("docs/doc.txt").getPath()));

        Long nbDocs = new IndexTask(spewer, inputQueueFactory, new Task<>(IndexTask.class.getName(), User.local(), map), null).call();

        assertThat(nbDocs).isEqualTo(1);
        DocumentQueue<String> outputQueue = outputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getOutputQueueNameFor(Stage.INDEX), String.class);
        assertThat(outputQueue).hasSize(1);
        assertThat(outputQueue.poll()).isEqualTo("bc6852541ef5200206a7a9740f3d2d62178a1f53b1aa5417ab426c6ec1f7cbc7");
    }

    @Test
    public void index_task_skips_legacy_poison_in_input_queue() throws Exception {
        DocumentQueue<Path> queue = inputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getQueueNameFor(Stage.INDEX), Path.class);
        queue.add(PipelineTask.PATH_POISON);
        queue.add(Paths.get(ClassLoader.getSystemResource("docs/doc.txt").getPath()));
        queue.add(PipelineTask.PATH_POISON);

        new IndexTask(spewer, inputQueueFactory, new Task<>(IndexTask.class.getName(), User.local(), map), null).call();

        DocumentQueue<String> outputQueue = outputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getOutputQueueNameFor(Stage.INDEX), String.class);
        assertThat(outputQueue).hasSize(1); // only the real document id, POISON skipped
        assertThat(outputQueue.poll()).isEqualTo("bc6852541ef5200206a7a9740f3d2d62178a1f53b1aa5417ab426c6ec1f7cbc7");
    }

    @Test(timeout = 20000)
    public void index_task_waits_for_quiet_queue_with_positive_queue_poll() throws Exception {
        Map<String, Object> waitMap = new HashMap<>(map) {{
            put("queuePoll", "1s"); // block up to 1s on an empty queue before concluding drained
        }};
        DocumentQueue<Path> queue = inputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getQueueNameFor(Stage.INDEX), Path.class);
        queue.add(Paths.get(ClassLoader.getSystemResource("docs/doc.txt").getPath()));

        long start = System.currentTimeMillis();
        Long nbDocs = new IndexTask(spewer, inputQueueFactory, new Task<>(IndexTask.class.getName(), User.local(), waitMap), null).call();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(nbDocs).isEqualTo(1);
        assertThat(elapsed).isGreaterThanOrEqualTo(900); // proves it blocked on the empty queue instead of exiting instantly
    }
```

Add the import at the top if not present:

```java
import org.icij.datashare.tasks.PipelineTask;
```
(It is the same package, so `PipelineTask.PATH_POISON` resolves without an import; keep the existing `import static ...PipelineTask.STRING_POISON;` only if still referenced — after this change it is not, so remove that static import to avoid an unused-import error.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl datashare-app -am test -Dtest=IndexTaskIntTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `index_task_forwards_only_document_ids_without_poison` sees size 2 (spewer still adds POISON); poison-skip test indexes/forwards a POISON; quiet-queue test may exit early (queuePoll not honored yet / still drains on POISON).

- [ ] **Step 3: Remove the spewer's POISON production**

In `datashare-index/.../ElasticsearchSpewer.java`, change `close()`:

```java
    @Override
    public void close() throws Exception {
        // end-of-stream is signalled by a quiet queue (poll timeout), not a POISON pill
    }
```

- [ ] **Step 4: Change IndexTask to drain a quiet queue and skip POISON**

In `IndexTask.java`, add imports:

```java
import java.time.Duration;
```

Wrap the consumer to skip POISON (replace the `progressTrackConsumer` assignment, ~lines 73-79):

```java
        progressTrackConsumer = path -> {
            if (isPoison(path)) {
                logger.debug("skipping legacy POISON entry from queue {}", inputQueue.getName());
                return;
            }
            consumer.accept(path);
            processed.incrementAndGet();
            if (progressCallback != null) {
                progressCallback.apply(getProgressRate());
            }
        };
```

After the drainer is constructed (~line 84), apply the pipeline poll timeout (default 120s, or the operator's explicit value including 0):

```java
        drainer = new DocumentQueueDrainer<>(inputQueue, progressTrackConsumer).configure(allTaskOptions);
        drainer.setPollTimeout(pipelineQueuePoll(propertiesProvider));
```

In `call()`, drain without a poison (line 92):

```java
            totalToProcess = drainer.drain().get();
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -pl datashare-app -am test -Dtest=IndexTaskIntTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (all IndexTaskIntTest methods).

Also compile-check the spewer module and its tests:

Run: `mvn -pl datashare-index -am test -Dtest=ElasticsearchSpewerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (the nlp-queue size assertion there is checked before `close()`, so it is unaffected).

- [ ] **Step 6: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tasks/IndexTask.java \
        datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchSpewer.java \
        datashare-app/src/test/java/org/icij/datashare/tasks/IndexTaskIntTest.java
git commit -m "feat(tasks): terminate indexing on a quiet queue and skip legacy POISON"
```

---

### Task 4: DeduplicateTask terminates on a quiet queue, skips and no longer forwards POISON

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/DeduplicateTask.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/DeduplicateTaskTest.java`

**Interfaces:**
- Consumes: `PipelineTask.isPoison(Path)`, `PipelineTask.pipelineQueuePoll(PropertiesProvider)` (Task 1).
- Produces: `transferToOutputQueue` drains the input by polling with the pipeline poll window, terminating when a poll returns `null`; POISON entries are skipped; the output queue receives only real paths (no POISON).

- [ ] **Step 1: Rewrite the tests (failing)**

Replace the body of `DeduplicateTaskTest.java` with (adds a short `queuePoll` so polls terminate fast; removes forwarded-POISON expectations; adds skip + quiet-queue cases):

```java
package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.junit.Test;

import java.nio.file.Path;
import java.util.Map;

import static java.nio.file.Paths.get;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.tasks.PipelineTask.PATH_POISON;

public class DeduplicateTaskTest {
    DocumentCollectionFactory<Path> docCollectionFactory = new MemoryDocumentCollectionFactory<>();
    Map<String, Object> defaultOpts = Map.of("queueName", "test:queue", "stages", "DEDUPLICATE", "queuePoll", "10ms");
    DeduplicateTask task = new DeduplicateTask(docCollectionFactory, new Task<>(DeduplicateTask.class.getName(), User.local(), defaultOpts), null);

    @Test(timeout = 2000)
    public void test_filter_empty() throws Exception {
        assertThat(new DeduplicateTask(docCollectionFactory, new Task<>(DeduplicateTask.class.getName(), User.local(), defaultOpts), null).call()).isEqualTo(0);
    }

    @Test(timeout = 2000)
    public void test_filter_queue_removes_duplicates() throws Exception {
        docCollectionFactory.createQueue("test:queue:deduplicate", Path.class).put(get("/path/to/doc"));
        docCollectionFactory.createQueue("test:queue:deduplicate", Path.class).put(get("/path/to/doc"));

        assertThat(new DeduplicateTask(docCollectionFactory, new Task<>(DeduplicateTask.class.getName(), User.local(), defaultOpts), null).call()).isEqualTo(1);

        assertThat(docCollectionFactory.createQueue("test:queue:index", Path.class).size()).isEqualTo(1); // no POISON
    }

    @Test(timeout = 2000)
    public void test_transfer_terminates_on_quiet_queue_without_poison() throws Exception {
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
    public void test_skips_legacy_poison_without_forwarding_it() throws Exception {
        task.inputQueue.put(get("/path/to/doc1"));
        task.inputQueue.put(PATH_POISON);
        task.inputQueue.put(get("/path/to/doc2"));

        task.transferToOutputQueue();

        DocumentQueue<Path> outputQueue = docCollectionFactory.createQueue(task.getOutputQueueName(), Path.class);
        assertThat(outputQueue.size()).isEqualTo(2); // POISON skipped, not forwarded
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc1");
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc2");
    }

    @Test(timeout = 2000)
    public void test_conditional_transfer_to_output_queue() throws Exception {
        task.inputQueue.put(get("/path/to/doc1"));
        task.inputQueue.put(get("/path/to/doc2"));

        task.transferToOutputQueue(p -> p.toString().contains("1"));

        assertThat(task.inputQueue.isEmpty()).isTrue();
        DocumentQueue<Path> outputQueue = docCollectionFactory.createQueue(task.getOutputQueueName(), Path.class);
        assertThat(outputQueue.size()).isEqualTo(1);
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc1");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl datashare-app -am test -Dtest=DeduplicateTaskTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL/HANG-then-fail — current `take()` loop blocks (no POISON) or NPEs on `null.equals`; sizes include a forwarded POISON. (If a case hangs, that is the red state; the rewrite fixes it.)

- [ ] **Step 3: Rewrite transferToOutputQueue**

In `DeduplicateTask.java`, add imports:

```java
import java.util.concurrent.TimeUnit;
```

Replace `transferToOutputQueue(Predicate<Path> filter)` (lines 50-62):

```java
    long transferToOutputQueue(Predicate<Path> filter) throws Exception {
        long originalSize = inputQueue.size();
        long pollMillis = pipelineQueuePoll(propertiesProvider).toMillis();
        try (DocumentQueue<Path> outputQueue = factory.createQueue(getOutputQueueName(), Path.class)) {
            Path path;
            while ((path = inputQueue.poll(pollMillis, TimeUnit.MILLISECONDS)) != null) {
                if (isPoison(path)) {
                    logger.debug("skipping legacy POISON entry in queue {}", inputQueue.getName());
                    continue;
                }
                if (filter.test(path)) {
                    outputQueue.add(path);
                }
            }
            return originalSize - outputQueue.size();
        }
    }
```

(`removeDuplicates()` in `call()` is unchanged.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl datashare-app -am test -Dtest=DeduplicateTaskTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tasks/DeduplicateTask.java \
        datashare-app/src/test/java/org/icij/datashare/tasks/DeduplicateTaskTest.java
git commit -m "feat(tasks): deduplicate on a quiet queue and stop forwarding POISON"
```

---

### Task 5: ExtractNlpTask skips legacy POISON and terminates on timeout only

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/ExtractNlpTask.java:72-97`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/ExtractNlpTaskIntTest.java`

**Interfaces:**
- Consumes: `PipelineTask.isPoison(String)` (Task 1).
- Produces: NLP loop no longer terminates on POISON; a POISON entry is skipped (logged at debug) and the loop continues; termination is solely via the existing `NB_MAX_POLLS` empty-poll budget over `pollingInterval` seconds.

- [ ] **Step 1: Update the integration tests (failing)**

In `ExtractNlpTaskIntTest.java`, set a small `pollingInterval` so the timeout-based termination is fast, keep the POISON in the queue to prove it is skipped, and assert the indexer is never queried for `"POISON"`.

Change `@Before` `setUp()`:

```java
    @Before
    public void setUp() {
        initMocks(this);
        nlpTask = new ExtractNlpTask(indexer, pipeline, factory, new Task<>(ExtractNlpTask.class.getName(), User.local(), new HashMap<>(){{
            put("maxContentLength", "32");
            put("pollingInterval", "0.1");
        }}), null);
    }
```

Update `test_loop_consume_two_documents` to add the skip assertion (keep `queue.add(PipelineTask.STRING_POISON);`):

```java
        nlpTask.call();

        verify(pipeline).initialize(ENGLISH);
        verify(pipeline).process(doc);
        verify(indexer, never()).get(anyString(), eq("POISON"));
```

Add the needed static imports if missing:

```java
import static org.mockito.ArgumentMatchers.never; // if not already imported via Mockito.*
```
(`ExtractNlpTaskIntTest` already imports `org.mockito.Mockito.*`, which provides `never()`; and `eq`/`anyString` are already imported.)

Update the inline task in `test_progress` to also poll fast:

```java
        new ExtractNlpTask(indexer, pipeline, factory, new Task<>(ExtractNlpTask.class.getName(), User.local(), new HashMap<>() {{
            put("maxContentLength", "32");
            put("pollingInterval", "0.1");
        }}), callback).call();
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl datashare-app -am test -Dtest=ExtractNlpTaskIntTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: These currently still PASS (poison terminates today). The true red is the new `verify(indexer, never()).get(..., "POISON")` only once the loop changes — so first confirm by temporarily reasoning: today the loop exits ON poison before ever calling `get("POISON")`, so this assertion already passes. To get a real red-green, add the failing behavior test below instead.

Add a dedicated skip test to `ExtractNlpTaskIntTest`:

```java
    @Test(timeout = 2000)
    public void test_skips_legacy_poison_and_keeps_consuming() throws Exception {
        when(pipeline.getType()).thenReturn(Pipeline.Type.CORENLP);
        when(pipeline.initialize(any())).thenReturn(true);
        Document doc = createDoc("content").build();
        when(indexer.get(anyString(), eq("docId"))).thenReturn(doc);

        String queueName = new PipelineHelper(new PropertiesProvider()).getQueueNameFor(Stage.NLP);
        DocumentQueue<String> queue = factory.createQueue(queueName, String.class);
        queue.add(PipelineTask.STRING_POISON); // legacy poison BEFORE the real doc
        queue.add("docId");

        nlpTask.call();

        verify(pipeline).process(doc); // reached the doc that sat behind the poison
        verify(indexer, never()).get(anyString(), eq("POISON"));
    }
```

Run again:
Expected: FAIL — today the loop exits on the leading POISON and never processes `docId`, so `verify(pipeline).process(doc)` fails.

- [ ] **Step 3: Rewrite the NLP loop to skip poison**

In `ExtractNlpTask.java`, replace the `call()` loop (lines 76-96):

```java
        String docId;
        long nbMessages = 0;
        int nbMaxPolls = NB_MAX_POLLS;
        while (nbMaxPolls > 0) {
            docId = inputQueue.poll((long) (pollingIntervalSeconds * 1000), TimeUnit.MILLISECONDS);
            try {
                if (docId == null) {
                    logger.info("will poll document queue again for pollingInterval={} seconds ({}/{})", pollingIntervalSeconds, nbMaxPolls, NB_MAX_POLLS);
                    nbMaxPolls--;
                } else if (isPoison(docId)) {
                    logger.debug("skipping legacy POISON entry in queue {}", inputQueue.getName());
                } else {
                    findNamedEntities(project, docId);
                    nbMessages++;
                    processed.incrementAndGet();
                    progressCallback.apply(getProgressRate());
                }
            } catch (Throwable e) {
                logger.error("error in ExtractNlpTask loop", e);
            }
        }
        logger.info("exiting ExtractNlpTask loop after {} messages.", nbMessages);
        return nbMessages;
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl datashare-app -am test -Dtest=ExtractNlpTaskIntTest,ExtractNlpTaskTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (including `ExtractNlpTaskTest#test_exit_after_nb_max_attempts`, which already relied on the timeout).

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tasks/ExtractNlpTask.java \
        datashare-app/src/test/java/org/icij/datashare/tasks/ExtractNlpTaskIntTest.java
git commit -m "feat(tasks): skip legacy POISON during NER and rely on the poll timeout"
```

---

### Task 6: CategorizeTask configurable poll interval, skips POISON, stops forwarding POISON

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/CategorizeTask.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/CategorizeTaskIntTest.java`

**Interfaces:**
- Consumes: `PipelineTask.isPoison(String)` (Task 1); `DatashareCliOptions.POLLING_INTERVAL_SECONDS_OPT`, `DatashareCliOptions.DEFAULT_POLLING_INTERVAL_SEC`.
- Produces: CATEGORIZE reads `pollingInterval` (default 60s, matching the previous hardcoded value); a POISON entry on input is skipped; termination is via `NB_MAX_POLLS` empty polls; the output queue receives only real document ids (no trailing POISON).

- [ ] **Step 1: Update the integration tests (failing)**

In `CategorizeTaskIntTest.java`:

Add a fast poll interval to every `CategorizeTask` invocation and fix the output sizes (no forwarded POISON). Replace the `Map.of(...)` args in the four direct `CategorizeTask` constructions and the two `ENQUEUEIDX,CATEGORIZE` args maps so each includes `"pollingInterval", "0.1"`. For example:

```java
        CategorizeTask categorizeTask = new CategorizeTask(indexer, documentCollectionFactory, new Task<>(CategorizeTask.class.getName(),
                "categorizeTask1", User.local(), Map.of(DEFAULT_PROJECT_OPT, es.getIndexName(), "pollingInterval", "0.1")), NO_OP_PROGRESS);
```
```java
        CategorizeTask categorizeTask = new CategorizeTask(indexer, documentCollectionFactory, new Task<>(CategorizeTask.class.getName(),
                "categorizeTask1", User.local(), Map.of(QUEUE_NAME_OPT, "foo", DEFAULT_PROJECT_OPT, es.getIndexName(), "pollingInterval", "0.1")), NO_OP_PROGRESS);
```

For the two ENQUEUEIDX tests, change the args map to a mutable/expanded map that adds `pollingInterval`:

```java
        Map<String, Object> args = Map.of(
                DEFAULT_PROJECT_OPT, es.getIndexName(),
                "stages", "ENQUEUEIDX,CATEGORIZE",
                "pollingInterval", "0.1");
```

Fix the output-queue size assertions (POISON no longer forwarded):

```java
        // test_categorization
        assertThat(outPutQueue.size()).isEqualTo(3); // no POISON
```
```java
        // test_categorization_with_different_queue_name
        assertThat(outputQueue.size()).isEqualTo(2); // no POISON
```
```java
        // test_receiving_null_from_indexer
        assertThat(outPutQueue.size()).isEqualTo(1); // no POISON
```

(The `enqueueWithPoison`/`enqueuePoison` helpers stay: they seed a legacy POISON on the input queue, which the task must now skip. Termination comes from the `0.1s` poll timeout, not the POISON.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl datashare-app -am test -Dtest=CategorizeTaskIntTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — output sizes still include a forwarded POISON (4/3/2 vs expected 3/2/1); and/or the task ignores `pollingInterval` (still hardcoded 60s) so behavior is unchanged on that axis.

- [ ] **Step 3: Make the poll interval configurable, skip POISON, stop forwarding POISON**

In `CategorizeTask.java`:

Add imports:

```java
import static java.lang.String.valueOf;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_POLLING_INTERVAL_SEC;
import static org.icij.datashare.cli.DatashareCliOptions.POLLING_INTERVAL_SECONDS_OPT;
```

Remove the hardcoded interval constant (line 43) and add a field:

```java
    private static final int NB_MAX_POLLS = 3;
    private final float pollingIntervalSeconds;
```

In the constructor, read the option (default 60s, unchanged behavior):

```java
        pollingIntervalSeconds = Float.parseFloat(ofNullable((String) taskView.args.get(POLLING_INTERVAL_SECONDS_OPT)).orElse(DEFAULT_POLLING_INTERVAL_SEC));
```

Replace the `call()` loop (lines 60-92) with:

```java
        String docId;
        long nbMessages = 0;
        int nbMaxPolls = NB_MAX_POLLS;

        while (nbMaxPolls > 0) {
            docId = inputQueue.poll((long) (pollingIntervalSeconds * 1000), TimeUnit.MILLISECONDS);
            try {
                if (docId == null) {
                    logger.info("will poll document queue again for pollingInterval={} seconds ({}/{})", pollingIntervalSeconds, nbMaxPolls, NB_MAX_POLLS);
                    nbMaxPolls--;
                } else if (isPoison(docId)) {
                    logger.debug("skipping legacy POISON entry in queue {}", inputQueue.getName());
                } else {
                    Document retrievedFromIndexer = indexer.get(project.getName(), docId);
                    if (retrievedFromIndexer == null) {
                        logger.warn("Unable to retrieve document {} from indexer. Cannot add it's contentTypeCategory", docId);
                    } else {
                        enrichWithType(retrievedFromIndexer);
                    }
                    nbMessages++;
                    processed.incrementAndGet();
                    progressCallback.apply(getProgressRate());
                    if (!outputQueue.offer(docId)) {
                        logger.warn("unable to offer {} to queue {}", docId, outputQueue.getName());
                    }
                }
            } catch (Exception e) {
                logger.error("error in CategorizeTask loop", e);
            }
        }
        logger.info("exiting CategorizeTask loop after {} messages.", nbMessages);
        return nbMessages;
```

(Note the removed final `outputQueue.offer(STRING_POISON)` block — CATEGORIZE no longer forwards POISON.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl datashare-app -am test -Dtest=CategorizeTaskIntTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (all methods).

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tasks/CategorizeTask.java \
        datashare-app/src/test/java/org/icij/datashare/tasks/CategorizeTaskIntTest.java
git commit -m "feat(tasks): make categorize poll configurable, skip and stop forwarding POISON"
```

---

### Task 7: Full-suite verification

**Files:** none (verification only).

- [ ] **Step 1: Run the datashare-app suite**

Run: `mvn -pl datashare-app -am test`
Expected: PASS except the known-ignored failures: `ExtensionLoaderTest#test_load_jars`, `TaskManagersIntTest` (RabbitMQ), `TaskManagerAmqpTest#test_task_result_uri_result_type` (flake). Pay attention that `TaskWorkerLoopForPipelineTasksTest` still passes (it uses mock queues and a 1s await, so the 120s default cannot cause a hang) and that no IndexTask/Dedup test hangs.

- [ ] **Step 2: Run the datashare-tasks suite**

Run: `mvn -pl datashare-tasks -am test`
Expected: PASS except `TaskManagersIntTest` and `TaskManagerAmqpTest#test_task_result_uri_result_type` as above.

- [ ] **Step 3: Confirm no remaining POISON producers**

Run: `grep -rn "POISON" datashare-*/src/main/java`
Expected: only `PipelineTask.PATH_POISON` / `STRING_POISON` declarations and the `isPoison(...)` uses / debug log strings remain — no `add`/`put`/`offer` of POISON to any queue.

- [ ] **Step 4: No commit** (verification task; any fixes belong to the task that introduced the regression).

---

## Self-Review

**1. Spec coverage:**
- Goal 1 (stop producing POISON): Task 2 (ScanTask), Task 3 (ElasticsearchSpewer), Task 4 (DeduplicateTask), Task 6 (CategorizeTask). Covered.
- Goal 2 (terminate on quiet queue, safe default): Task 1 (`pipelineQueuePoll` + `queuePoll` help), Task 3 (IndexTask default 120s + `drain()`), Task 4 (DeduplicateTask poll timeout), Tasks 5/6 (`NB_MAX_POLLS` timeout). Covered.
- Goal 3 (skip legacy POISON silently): Task 3 (IndexTask), Task 4 (DeduplicateTask), Task 5 (ExtractNlpTask), Task 6 (CategorizeTask). Covered with dedicated skip tests.
- Goal 4 (no extract-lib change): honored — only `drain()` vs `drain(poison)` call-site change. Covered.
- Goal 5 (CLI backward compatibility): `queuePoll` reuses the existing key, no removed/renamed options or queues. Covered.

**2. Placeholder scan:** No TBD/TODO; every code step shows the full code and every command shows expected output.

**3. Type consistency:** `isPoison(Path)`, `isPoison(String)`, `pipelineQueuePoll(PropertiesProvider) -> Duration`, `QUEUE_POLL_OPT`, `DEFAULT_QUEUE_POLL`, `queuePoll(OptionParser)` are defined in Task 1 and used with identical signatures in Tasks 3-6. `pollingIntervalSeconds` is a `float` in both ExtractNlpTask and CategorizeTask. The drainer whole-second constraint is respected (IndexTask tests use `>= 1s`; DeduplicateTask polls directly at ms granularity).

**Note discovered during planning (beyond the spec's context list):** two poison touch-points the spec did not name — `ElasticsearchSpewer.close()` (produces the NLP-queue POISON that currently terminates NER) and `CategorizeTask`'s hardcoded 60s interval (must become configurable to test timeout-based termination). Both are folded into Tasks 3 and 6 respectively.
