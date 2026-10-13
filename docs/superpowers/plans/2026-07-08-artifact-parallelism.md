# ARTIFACT Stage Parallelism Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `ArtifactTask` drain its document queue across a `--parallelism`-sized worker pool instead of one thread, so a batch `ENQUEUEIDX,ARTIFACT` run uses the whole machine.

**Architecture:** `ArtifactTask.call()` builds a fixed thread pool of `parallelism` workers. Each worker owns its own `SourceExtractor` (created through a new overridable `createSourceExtractor()` seam) and runs the existing poll/fetch/extract loop. Shared counters become `AtomicLong`. Cancellation interrupts workers via `shutdownNow()`.

**Tech Stack:** Java 17, JUnit 4, Mockito, FEST assertions, `java.util.concurrent` (`ExecutorService`, `Executors`, `Future`), Maven.

## Global Constraints

- **Parallelism option:** reuse `--parallelism` (`DatashareCliOptions.PARALLELISM_OPT`). In `ArtifactTask`, resolve it with `.orElse(1)`. Do not add a new CLI option.
- **Default behavior split (must be preserved):** CLI batch mode always has the property present (joptsimple bakes the `--parallelism` default into the properties map), so batch runs parallelize. Server/on-demand tasks omit the property, so `.orElse(1)` keeps them single-threaded.
- **No in-flight root-document guard.** Concurrent identical-digest writes and duplicated parsing of shared roots are accepted; writes are content-addressed so last-writer-wins is harmless.
- **No changes to `ENQUEUEIDX`, `extract-lib`, or the public surface of `SourceExtractor`.**
- **Preserve existing log messages verbatim** except the startup line (which gains the worker count): the WARN `document <...> could not be retrieved ...`, the ERROR `N document(s) could not be retrieved from index ... re-run the ARTIFACT stage for them`, and the info `exiting ArtifactTask loop after processing N document(s).` must be unchanged so existing assertions keep passing.
- **Single-module test command** (from project memory): `mvn -pl datashare-app -am test -Dtest=ArtifactTaskTest -Dsurefire.failIfNoSpecifiedTests=false`. ES at `elasticsearch:9200`, Redis at `redis:6379` (not needed here: tests use in-memory factory + mock indexer).

---

## File Structure

- **Modify:** `datashare-app/src/main/java/org/icij/datashare/tasks/ArtifactTask.java` — add `parallelism` field, `createSourceExtractor()` seam, `runWorker(...)` loop, and rewrite `call()` to fan out over a pool. Single responsibility unchanged: build the artifact cache by draining the queue.
- **Modify:** `datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactTaskTest.java` — add concurrency, parallel-skip, non-fatal-failure, and default-single-threaded tests; parameterize `runArtifactTask` to accept a parallelism value.

No new files. The change is contained to one production class and its test.

---

## Task 1: Parallel worker pool in ArtifactTask

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/ArtifactTask.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactTaskTest.java`

**Interfaces:**
- Consumes: `PipelineTask<String>` protected members `inputQueue` (`DocumentQueue<String>`), `getDocument(Indexer, String, String, List<String>)`, `propertiesProvider`; `SourceExtractor(PropertiesProvider)`; `SourceExtractor.extractEmbeddedSources(Project, Document)`.
- Produces (relied on by Task 2 tests):
  - `protected SourceExtractor createSourceExtractor()` — overridable factory returning a per-worker extractor.
  - `public Long call()` — returns the total number of documents successfully extracted, aggregated across workers.
  - Constructor signature unchanged: `ArtifactTask(DocumentCollectionFactory<String>, Indexer, PropertiesProvider, Task<Long>, Function<Double,Void>)`.

- [ ] **Step 1: Write the failing concurrency test**

Add to `ArtifactTaskTest.java`. This proves two extractions run at the same time; it times out (fails) if the task is single-threaded. Add these imports at the top of the test file:

```java
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
```

Test method:

```java
@Test(timeout = 10000)
public void test_workers_run_concurrently() throws Exception {
    indexEmbeddedDoc();
    String secondId = "1111111111111111111111111111111111111111111111111111111111111111";
    mockIndexer.indexFile("prj", secondId,
            Path.of(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).toURI()),
            "message/rfc822");
    DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
    queue.add(EMBEDDED_DOC_SHA256);
    queue.add(secondId);

    CountDownLatch bothInFlight = new CountDownLatch(2);
    PropertiesProvider props = new PropertiesProvider(Map.of(
            "artifactDir", artifactDir.getRoot().toString(),
            "defaultProject", "prj",
            "pollingInterval", "1",
            "parallelism", "2"));
    Task<Long> task = new Task<>(ArtifactTask.class.getName(), User.local(), new HashMap<>());

    ArtifactTask artifactTask = new ArtifactTask(factory, mockEs, props, task, null) {
        @Override
        protected SourceExtractor createSourceExtractor() {
            return new SourceExtractor(props) {
                @Override
                public org.apache.tika.parser.RecursiveParserWrapper.RecursiveParserWrapperHandler // placeholder, replaced below
                        extractEmbeddedSources(Project project, Document document) {
                    return null;
                }
            };
        }
    };

    Long processed = artifactTask.call();
    assertThat(processed).isEqualTo(2);
}
```

> Note for the implementer: `extractEmbeddedSources` returns `org.apache.tika.metadata.TikaDocument`? No — it returns `org.icij.extract.document.TikaDocument`. Use the correct return type. To keep the override compilable and to actually prove concurrency, replace the anonymous `SourceExtractor` body with the latch-aware version in Step 1b below rather than the placeholder shown above.

- [ ] **Step 1b: Replace the extractor override with the latch-aware version**

Import the real return type and wire the latch. Replace the `createSourceExtractor()` override in the test with:

```java
import org.icij.extract.document.TikaDocument;
```

```java
    ArtifactTask artifactTask = new ArtifactTask(factory, mockEs, props, task, null) {
        @Override
        protected SourceExtractor createSourceExtractor() {
            return new SourceExtractor(props) {
                @Override
                public TikaDocument extractEmbeddedSources(Project project, Document document) {
                    bothInFlight.countDown();
                    try {
                        // both workers must arrive before either proceeds;
                        // with a single worker the second countDown never happens -> timeout
                        if (!bothInFlight.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("workers did not run concurrently");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                }
            };
        }
    };
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl datashare-app -am test -Dtest=ArtifactTaskTest#test_workers_run_concurrently -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error `createSourceExtractor()` does not exist (or, once it exists, a 10s timeout because the single-threaded loop never lets both extractions be in flight).

- [ ] **Step 3: Implement the worker pool in ArtifactTask**

Replace the body of `ArtifactTask.java` with the following. Keep the class annotations, package, and existing static imports.

```java
package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACT_DIR_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_POLLING_INTERVAL_SEC;
import static org.icij.datashare.cli.DatashareCliOptions.PARALLELISM_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.POLLING_INTERVAL_SECONDS_OPT;

@TemporalSingleActivityWorkflow(name = "artifact", activityOptions = @ActivityOpts(timeout = "P1D"))
@TaskGroup(TaskGroupType.Java)
public class ArtifactTask extends PipelineTask<String> {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final Project project;
    private final Path artifactDir;
    private final int pollingInterval;
    private final int parallelism;

    @Inject
    public ArtifactTask(DocumentCollectionFactory<String> factory, Indexer indexer, PropertiesProvider propertiesProvider, @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> updateCallback) {
        super(Stage.ARTIFACT, taskView.getUser(), factory, propertiesProvider, String.class);
        this.indexer = indexer;
        project = Project.project(propertiesProvider.get(DEFAULT_PROJECT_OPT).orElse(DEFAULT_DEFAULT_PROJECT));
        pollingInterval = Integer.parseInt(propertiesProvider.get(POLLING_INTERVAL_SECONDS_OPT).orElse(DEFAULT_POLLING_INTERVAL_SEC));
        parallelism = propertiesProvider.get(PARALLELISM_OPT).map(Integer::parseInt).orElse(1);
        artifactDir = Path.of(propertiesProvider.get(ARTIFACT_DIR_OPT).orElseThrow(() -> new IllegalArgumentException(String.format("cannot create artifact task with empty %s", ARTIFACT_DIR_OPT))));
    }

    @Override
    public Long call() throws Exception {
        super.call();
        logger.info("creating artifact cache in {} for project {} from queue {} with {} worker(s) and polling interval {}s", artifactDir, project, inputQueue.getName(), parallelism, pollingInterval);
        AtomicLong nbDocs = new AtomicLong(0);
        AtomicLong nbSkipped = new AtomicLong(0);
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, namedThreadFactory("artifact-worker"));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < parallelism; i++) {
                futures.add(executor.submit(() -> runWorker(nbDocs, nbSkipped)));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    logger.error("artifact worker terminated abnormally", e.getCause());
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
        }
        if (nbSkipped.get() > 0) {
            logger.error("{} document(s) could not be retrieved from index {} and got no artifact cache, re-run the ARTIFACT stage for them", nbSkipped.get(), project.name);
        }
        logger.info("exiting ArtifactTask loop after processing {} document(s).", nbDocs.get());
        return nbDocs.get();
    }

    private void runWorker(AtomicLong nbDocs, AtomicLong nbSkipped) {
        SourceExtractor extractor = createSourceExtractor();
        List<String> sourceExcludes = List.of("content", "content_translated");
        try {
            String docId;
            while ((docId = inputQueue.poll(pollingInterval, TimeUnit.SECONDS)) != null) {
                try {
                    Document doc = getDocument(indexer, project.name, docId, sourceExcludes);
                    if (doc == null) {
                        nbSkipped.incrementAndGet();
                        continue;
                    }
                    extractor.extractEmbeddedSources(project, doc);
                    nbDocs.incrementAndGet();
                } catch (Throwable e) {
                    logger.error("error in ArtifactTask loop", e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected SourceExtractor createSourceExtractor() {
        return new SourceExtractor(propertiesProvider);
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(0);
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(prefix + "-" + counter.incrementAndGet());
            return thread;
        };
    }
}
```

- [ ] **Step 4: Run the concurrency test to verify it passes**

Run: `mvn -pl datashare-app -am test -Dtest=ArtifactTaskTest#test_workers_run_concurrently -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Run the full ArtifactTaskTest to confirm no regression**

Run: `mvn -pl datashare-app -am test -Dtest=ArtifactTaskTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS — `test_missing_artifact_dir`, `test_create_artifact_cache_one_file`, `test_skip_document_not_found_in_index_with_warning`, and `test_workers_run_concurrently` all green.

- [ ] **Step 6: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tasks/ArtifactTask.java datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactTaskTest.java
git commit -m "feat(artifact): drain queue across a --parallelism-sized worker pool"
```

---

## Task 2: Regression coverage under parallelism

These tests confirm the refactor preserved the existing skip/failure/default behavior when more than one worker runs. They exercise the code delivered in Task 1; they are expected to pass immediately (green), acting as guards against future regressions.

**Files:**
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactTaskTest.java`

**Interfaces:**
- Consumes: `ArtifactTask.call()`, `ArtifactTask.createSourceExtractor()` seam, `MockIndexer.indexFile(...)`, `factory.createQueue(...)`, `LogbackCapturingRule logback` from Task 1 and existing test setup.
- Produces: none (test-only).

- [ ] **Step 1: Add a parameterized task runner helper**

Add this helper alongside the existing `runArtifactTask()` in `ArtifactTaskTest.java` (keep the existing zero-arg one, which callers still use):

```java
private Long runArtifactTask(int parallelism) throws Exception {
    return new ArtifactTask(factory, mockEs, new PropertiesProvider(Map.of(
            "artifactDir", artifactDir.getRoot().toString(),
            "defaultProject", "prj",
            "pollingInterval", "1",
            "parallelism", String.valueOf(parallelism))),
            new Task<>(ArtifactTask.class.getName(), User.local(), new HashMap<>()), null)
            .call();
}
```

- [ ] **Step 2: Write the parallel-skip and non-fatal-failure tests**

```java
@Test(timeout = 10000)
public void test_skip_counted_under_parallelism() throws Exception {
    indexEmbeddedDoc();
    DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
    queue.add("unknownId");
    queue.add(EMBEDDED_DOC_SHA256);

    Long numberOfDocuments = runArtifactTask(2);

    assertThat(numberOfDocuments).isEqualTo(1);
    assertThat(logback.logs(Level.WARN)).contains("document <unknownId> could not be retrieved from index prj (missing document or index fetch error), skipping");
    assertThat(logback.logs(Level.ERROR)).contains("1 document(s) could not be retrieved from index prj and got no artifact cache, re-run the ARTIFACT stage for them");
}

@Test(timeout = 10000)
public void test_per_document_failure_is_non_fatal() throws Exception {
    indexEmbeddedDoc();
    String failingId = "2222222222222222222222222222222222222222222222222222222222222222";
    mockIndexer.indexFile("prj", failingId,
            Path.of(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).toURI()),
            "message/rfc822");
    DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
    queue.add(failingId);
    queue.add(EMBEDDED_DOC_SHA256);

    PropertiesProvider props = new PropertiesProvider(Map.of(
            "artifactDir", artifactDir.getRoot().toString(),
            "defaultProject", "prj",
            "pollingInterval", "1",
            "parallelism", "2"));
    ArtifactTask task = new ArtifactTask(factory, mockEs, props,
            new Task<>(ArtifactTask.class.getName(), User.local(), new HashMap<>()), null) {
        @Override
        protected SourceExtractor createSourceExtractor() {
            return new SourceExtractor(props) {
                @Override
                public TikaDocument extractEmbeddedSources(Project project, Document document) throws org.apache.tika.exception.TikaException {
                    if (document.getId().equals(failingId)) {
                        throw new org.apache.tika.exception.TikaException("boom");
                    }
                    return null;
                }
            };
        }
    };

    Long numberOfDocuments = task.call();

    assertThat(numberOfDocuments).isEqualTo(1);
    assertThat(logback.logs(Level.ERROR)).contains("error in ArtifactTask loop");
}
```

- [ ] **Step 3: Write the default-single-threaded test**

Confirms that when `parallelism` is absent (server/on-demand shape) the task still drains correctly with one worker.

```java
@Test(timeout = 10000)
public void test_default_is_single_threaded_and_still_drains() throws Exception {
    indexEmbeddedDoc();
    DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
    queue.add(EMBEDDED_DOC_SHA256);

    // no "parallelism" key -> ArtifactTask resolves .orElse(1)
    Long numberOfDocuments = new ArtifactTask(factory, mockEs, new PropertiesProvider(Map.of(
            "artifactDir", artifactDir.getRoot().toString(),
            "defaultProject", "prj",
            "pollingInterval", "1")),
            new Task<>(ArtifactTask.class.getName(), User.local(), new HashMap<>()), null)
            .call();

    assertThat(numberOfDocuments).isEqualTo(1);
    assertThat(artifactDir.getRoot().toPath().resolve("prj/6a/bb/6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e/raw").toFile()).isFile();
}
```

- [ ] **Step 4: Run the new tests to verify they pass**

Run: `mvn -pl datashare-app -am test -Dtest=ArtifactTaskTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS — all tests green, including the three added here.

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactTaskTest.java
git commit -m "test(artifact): cover skip, failure, and default behavior under parallelism"
```

---

## Self-Review

**1. Spec coverage:**
- Worker pool sized by `--parallelism` → Task 1 Step 3 (`Executors.newFixedThreadPool(parallelism, ...)`).
- Per-worker `SourceExtractor` via `createSourceExtractor()` seam → Task 1 Step 3.
- `.orElse(1)` default and CLI-vs-server split → Task 1 Step 3 (code) + Global Constraints + Task 2 Step 3 (test).
- `AtomicLong` counters and unchanged return value → Task 1 Step 3.
- Cancellation via `shutdownNow()` and non-hanging on worker death → Task 1 Step 3 (`InterruptedException` handling + `ExecutionException` catch + `finally`).
- Preserved log messages → Global Constraints + Task 2 Step 2 assertions.
- Named worker threads → Task 1 Step 3 (`namedThreadFactory`).
- Tests: parallel drain / concurrency (Task 1), skip counting (Task 2), non-fatal failure (Task 2), default single-threaded (Task 2). Spec test #1 "parallel drain processes every id" is covered by the concurrency test asserting `processed == 2` over two queued ids; the real-artifact-file assertion remains in the existing `test_create_artifact_cache_one_file`.
- Non-goals (no root guard, no progress callback, no extract-lib change) → respected; nothing in the plan touches them.

**2. Placeholder scan:** The only placeholder was the deliberate wrong return type in Task 1 Step 1, immediately corrected in Step 1b with a note. No `TBD`/`TODO`/"add error handling" left.

**3. Type consistency:** `createSourceExtractor()` returns `SourceExtractor` in both the production seam and every test override. `extractEmbeddedSources(Project, Document)` returns `org.icij.extract.document.TikaDocument` in production and all overrides. `call()` returns `Long` (autoboxed from `nbDocs.get()`) consistently. Counters are `AtomicLong` throughout.

---

## Execution note

The concurrency test in Task 1 uses the `createSourceExtractor()` seam that Task 1 Step 3 introduces, so Task 1's test and implementation are committed together (the test cannot compile before the seam exists). Task 2 is test-only and independently reviewable.
