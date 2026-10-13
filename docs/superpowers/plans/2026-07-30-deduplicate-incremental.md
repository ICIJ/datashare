# DeduplicateTask Per-Entry Dedup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `DeduplicateTask` deduplicate every entry as it drains, so a duplicate path enqueued by a still-running SCAN cannot reach the index queue.

**Architecture:** Replace the single upfront `inputQueue.removeDuplicates()` call with a `HashSet<Path>` consulted inside the existing `transferToOutputQueue(Predicate<Path>)` filter. The drain loop, the upstream-producer gate and the cancellation handling all stay exactly as they are: only the predicate and the duplicate counting change. Then remove the now-dead return value of `transferToOutputQueue` and its no-argument overload.

**Tech Stack:** Java 17, Maven, JUnit 4, fest-assert 1.x (`org.fest.assertions.Assertions.assertThat`), Mockito. Queues come from `DocumentCollectionFactory`; tests use the in-memory implementations.

## Global Constraints

- Branch: `fix/2296-incremental-deduplicate`, based on `refactor/remove-poison-pill` (PR #2289). Do not rebase onto `main`: the code depends on `transferToOutputQueue`, `drained(taskRepository)` and `UPSTREAM_TASK_ID` from that PR.
- Spec: `docs/superpowers/specs/2026-07-30-deduplicate-incremental-design.md`. Issue: [#2296](https://github.com/ICIJ/datashare/issues/2296).
- Test command, exactly this shape: `mvn -pl datashare-app -am test -Dtest=DeduplicateTaskTest -Dsurefire.failIfNoSpecifiedTests=false`. Never drop `-am`. Multiple classes are comma-separated in a single `-Dtest` value; a second `-Dtest` flag silently wins and gives a fake green.
- Commit messages: semantic prefix, no body, no co-author trailers, no "Generated with Claude".
- No em dash (`—`) in any comment, message or doc text.
- Do not add a legacy `PATH_POISON` skip to `DeduplicateTask`. It is explicitly out of scope in the spec: `IndexTask.java:84` already skips it downstream.
- Do not commit anything under `docs/superpowers/`. It is gitignored (`.gitignore:23`) and the user's CLAUDE.md forbids committing superpowers files.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `datashare-app/src/main/java/org/icij/datashare/tasks/DeduplicateTask.java` | The DEDUPLICATE pipeline stage: drain the scan queue into the index queue, dropping duplicate paths. | Modify `call()` (lines 40-47) and `transferToOutputQueue` (lines 49-86). |
| `datashare-app/src/test/java/org/icij/datashare/tasks/DeduplicateTaskTest.java` | Behaviour of that stage. | Add one test, adjust two existing call sites, widen one field type. |

No new files. No other production file is touched.

### Current state of the two methods you will edit

`DeduplicateTask.java:40-51` today:

```java
    @Override
    public Long call() throws Exception {
        super.call();
        int duplicates = inputQueue.removeDuplicates();
        transferToOutputQueue();
        logger.info("removed {} duplicate paths in inputQueue {}", duplicates, inputQueue.getName());
        return (long)duplicates;
    }

    long transferToOutputQueue() throws Exception {
        return transferToOutputQueue(p -> true);
    }
```

`DeduplicateTask.java:53-56` and `:84` today (the parts that change in Task 2):

```java
    long transferToOutputQueue(Predicate<Path> filter) throws Exception {
        long originalSize = inputQueue.size();
        try (DocumentQueue<Path> outputQueue = factory.createQueue(getOutputQueueName(), Path.class)) {
            while (!Thread.currentThread().isInterrupted()) {
...
            return originalSize - outputQueue.size();
        }
    }
```

---

## Task 1: Dedup per entry instead of once at startup

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/DeduplicateTask.java:40-47`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/DeduplicateTaskTest.java`

**Interfaces:**
- Consumes, all already present on this branch: `PipelineTask.transferToOutputQueue(Predicate<Path>)` returning `long`, `PipelineTask.drained(TaskRepository)`, `PipelineTask.UPSTREAM_TASK_ID` (the `String` constant `"upstreamTaskId"`), `PipelineTask.inputQueue`.
- Produces: `DeduplicateTask.call()` keeps its signature `public Long call() throws Exception` and its contract, the number of duplicate paths dropped. Task 2 relies on nothing new from this task.

- [ ] **Step 1: Write the failing test**

Add to `DeduplicateTaskTest.java`. Two supporting edits come first, both required for the test to compile:

Widen the factory field so the test can pre-seed a queue, replacing line 19:

```java
    MemoryDocumentCollectionFactory<Path> docCollectionFactory = new MemoryDocumentCollectionFactory<>();
```

Add these imports:

```java
import org.icij.datashare.asynctasks.TaskResult;
import org.icij.extract.queue.MemoryDocumentQueue;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.icij.datashare.PropertiesProvider.DEFAULT_QUEUE_CAPACITY;
```

Then the test itself:

```java
    @Test(timeout = 30000)
    public void test_a_duplicate_enqueued_after_the_drain_started_is_dropped() throws Exception {
        Task<Long> scan = new Task<>(ScanTask.class.getName(), User.local(), Map.of());
        scan.setState(Task.State.RUNNING);
        taskRepository.insert(scan, null);
        // counted down by the queue itself, so the test never sleeps to know the drain has already
        // polled an empty queue: that is the moment the old upfront removeDuplicates() is behind us
        CountDownLatch firstEmptyPoll = new CountDownLatch(1);
        DocumentQueue<Path> queue = new MemoryDocumentQueue<>("test:queue:deduplicate", DEFAULT_QUEUE_CAPACITY) {
            @Override
            public Path poll() {
                Path polled = super.poll();
                if (polled == null) {
                    firstEmptyPoll.countDown();
                }
                return polled;
            }
        };
        // pre-seed the factory: the task resolves its input queue by name in its constructor
        docCollectionFactory.queues.put("test:queue:deduplicate", queue);
        queue.put(get("/path/to/doc"));
        Map<String, Object> args = Map.of("queueName", "test:queue", "stages", "DEDUPLICATE",
                PipelineTask.UPSTREAM_TASK_ID, scan.id);
        DeduplicateTask task = new DeduplicateTask(docCollectionFactory, taskRepository,
                new Task<>(DeduplicateTask.class.getName(), User.local(), args), null);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Long> dropped = executor.submit((Callable<Long>) task::call);
            assertThat(firstEmptyPoll.await(20, SECONDS)).isTrue();
            queue.put(get("/path/to/doc")); // the duplicate SCAN enqueues mid-drain
            scan.setResult(new TaskResult<>(0L));

            assertThat(dropped.get(20, SECONDS)).isEqualTo(1L);
            assertThat(docCollectionFactory.createQueue("test:queue:index", Path.class).size()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }
```

Why this works: `scan` is stored by reference in `TaskRepositoryMemory`, so `setResult` makes it terminal for the gate without a second `insert`. The drain forwards the first path, polls empty, counts the latch down, sees the upstream still RUNNING and sleeps a poll interval. The test then enqueues the duplicate and finishes the upstream, so the next poll picks up the duplicate and the one after ends the drain.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl datashare-app -am test -Dtest=DeduplicateTaskTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL on `test_a_duplicate_enqueued_after_the_drain_started_is_dropped`, with `expected:<1> but was:<-1>`. The `-1` is not a typo: the current code forwards the duplicate, so the output queue holds 2 while `originalSize` was sampled as 1, and `transferToOutputQueue` returns `1 - 2`. The other four tests must pass.

If instead every test passes, the test is not exercising the bug. Most likely the factory field was left as `DocumentCollectionFactory<Path>` and the pre-seeded queue was ignored, or the queue name does not match `getInputQueueName()`. Fix that before continuing.

- [ ] **Step 3: Write the minimal implementation**

Replace `call()` at `DeduplicateTask.java:40-47` with:

```java
    @Override
    public Long call() throws Exception {
        super.call();
        // ponytail: on-heap, roughly 190 bytes per path, so about 2GB at 10M paths. Back it with
        // factory.createMap if a corpus that size ever needs this stage.
        Set<Path> seen = new HashSet<>();
        long[] dropped = {0};
        // dedup per entry, not in one upfront pass: SCAN keeps enqueueing while this drain runs
        transferToOutputQueue(path -> {
            if (seen.add(path)) {
                return true;
            }
            dropped[0]++;
            return false;
        });
        logger.info("removed {} duplicate paths in inputQueue {}", dropped[0], inputQueue.getName());
        return dropped[0];
    }
```

Add these imports to `DeduplicateTask.java`:

```java
import java.util.HashSet;
import java.util.Set;
```

`long[]` rather than `AtomicLong` on purpose: the drain is single-threaded, and an atomic would imply concurrency that does not exist. The `Predicate` lambda cannot close over a mutable local any other way.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -pl datashare-app -am test -Dtest=DeduplicateTaskTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, `Tests run: 5, Failures: 0, Errors: 0`.

`test_filter_queue_removes_duplicates` must still return 1 for two identical paths, now counted by the predicate rather than by `removeDuplicates()`. If it returns 0, the predicate is returning `true` for the second occurrence, which means `Path` equality is not doing what you expect: `Paths.get("/path/to/doc")` instances are equal, so check the predicate logic rather than the test.

- [ ] **Step 5: Verify no other stage regressed**

Run: `mvn -pl datashare-app -am test -Dtest=DeduplicateTaskTest,UpstreamGateTest,CliAppTest,IndexTaskIntTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS for all four classes. These are the classes that exercise the drain and the upstream gate this task's predicate runs inside.

- [ ] **Step 6: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tasks/DeduplicateTask.java datashare-app/src/test/java/org/icij/datashare/tasks/DeduplicateTaskTest.java
git commit -m "fix(tasks): deduplicate each queue entry instead of once at startup"
```

---

## Task 2: Drop the dead return value and the no-argument overload

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/DeduplicateTask.java:49-53` and `:84`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/DeduplicateTaskTest.java:43` and `:57`

**Interfaces:**
- Consumes: `DeduplicateTask.call()` from Task 1, which already ignores the return value of `transferToOutputQueue`.
- Produces: `void transferToOutputQueue(Predicate<Path> filter) throws Exception`. The `long transferToOutputQueue()` overload no longer exists. Nothing outside this file calls either.

Why: `originalSize - outputQueue.size()` is meaningless once the producer runs concurrently. `originalSize` is sampled before the drain starts, so it misses everything SCAN enqueues afterwards, and the output queue may already hold entries from an earlier run. Task 1 left it with no caller that reads it.

- [ ] **Step 1: Confirm nothing reads the return value**

Run: `grep -rn "transferToOutputQueue" --include="*.java" . | grep -v target | grep -v .worktrees`

Expected: exactly four hits, all in the two files above. The two test hits are statement calls that discard the result. If any hit assigns or asserts on the result, stop and report it: the spec assumed none do.

- [ ] **Step 2: Make the method void**

In `DeduplicateTask.java`, delete the no-argument overload entirely:

```java
    long transferToOutputQueue() throws Exception {
        return transferToOutputQueue(p -> true);
    }
```

Change the remaining signature and delete the `originalSize` sampling, so lines 53-54 become:

```java
    void transferToOutputQueue(Predicate<Path> filter) throws Exception {
        try (DocumentQueue<Path> outputQueue = factory.createQueue(getOutputQueueName(), Path.class)) {
```

And replace the final `return` statement (line 84) with nothing, leaving the try-with-resources block ending after the interrupt check:

```java
            // Thread.interrupted() tests AND clears: TaskWorkerLoop never clears the flag itself, so
            // leaving it set would leak the interrupt onto the runner thread and make the next task
            // start already cancelled.
            if (Thread.interrupted()) {
                throw new InterruptedException("cancelled while draining " + inputQueue.getName());
            }
        }
    }
```

- [ ] **Step 3: Update the two test call sites**

In `DeduplicateTaskTest.java`, line 43 becomes:

```java
        task.transferToOutputQueue(p -> true);
```

Line 57 is already passing a predicate and needs no change:

```java
        task.transferToOutputQueue(p -> p.toString().contains("1"));
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -pl datashare-app -am test -Dtest=DeduplicateTaskTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, `Tests run: 5, Failures: 0, Errors: 0`. This step is a refactor with no behaviour change, so a failure means a compile error or a dropped statement, not a logic bug.

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tasks/DeduplicateTask.java datashare-app/src/test/java/org/icij/datashare/tasks/DeduplicateTaskTest.java
git commit -m "refactor(tasks): drop the meaningless transferToOutputQueue return value"
```

---

## Task 3: Verify against the issue and close the loop

**Files:** none modified.

**Interfaces:** none.

- [ ] **Step 1: Run the full datashare-app suite**

Run: `mvn -pl datashare-app -am test`

Expected: the only failures are the four classes that fail for environment reasons on any branch of this repo. Compare against `git stash && mvn -pl datashare-app -am test` on the untouched branch if you are unsure which those are: any class failing on both is pre-existing and not yours.

- [ ] **Step 2: Confirm the new test fails without the fix**

```bash
git stash push -- datashare-app/src/main/java/org/icij/datashare/tasks/DeduplicateTask.java
mvn -pl datashare-app -am test -Dtest=DeduplicateTaskTest -Dsurefire.failIfNoSpecifiedTests=false
git stash pop
```

Expected: a compile error, since Task 2 changed the test call sites to match the new signature. That is fine and is itself proof the two are coupled. Record the Task 1 Step 2 failure output as the evidence instead: that run had the test failing on unfixed production code with the test compiling cleanly.

- [ ] **Step 3: Report, do not push**

Summarise for the user: the two commits, the `Tests run: 5` result, and the Task 1 Step 2 failure output as evidence the new test catches the bug. State explicitly that nothing was pushed and that the branch sits on top of PR #2289, so it needs rebasing if that PR changes.

Do not push, do not open a PR, and do not comment on #2296 without being asked.

---

## Self-Review

**Spec coverage:**

| Spec requirement | Task |
|---|---|
| Dedup moves into the transfer predicate | Task 1 Step 3 |
| `inputQueue.removeDuplicates()` deleted | Task 1 Step 3, the replacement omits it |
| Return value stays "duplicates dropped", now counted for real | Task 1 Step 3, `return dropped[0]` |
| `long[]` rather than `AtomicLong`, with the reason | Task 1 Step 3 |
| Memory ceiling documented with a `ponytail:` comment naming `createMap` | Task 1 Step 3 |
| `transferToOutputQueue` becomes `void`, `originalSize` sampling deleted | Task 2 Step 2 |
| No-argument overload deleted, test inlines `p -> true` | Task 2 Steps 2 and 3 |
| No `PATH_POISON` skip added | Global Constraints, stated as out of scope |
| Existing four tests hold unchanged in behaviour | Task 1 Step 4, Task 2 Step 4 |
| New test: duplicate enqueued after the drain started is dropped | Task 1 Steps 1 and 2 |
| Success criterion: new test fails on unfixed source | Task 1 Step 2, with the exact expected `-1` |
| Success criterion: no change for pipelines without the DEDUPLICATE stage | Task 1 Step 5, the four-class regression run |

No gaps.

**Placeholder scan:** no TBD, no "handle edge cases", no "similar to Task N". Every code step carries the actual code. The one grep step states the exact expected hit count and what to do if it differs.

**Type consistency:** `transferToOutputQueue(Predicate<Path>)` is `long` in Task 1 and becomes `void` in Task 2, which is Task 2's whole point and is declared in both Interfaces blocks. `dropped` is `long[]` throughout. `seen` is `Set<Path>` throughout. `call()` returns `Long` in both tasks, matching `PipelineTask<Path> extends DefaultTask<Long>`. `docCollectionFactory` is `MemoryDocumentCollectionFactory<Path>` from Task 1 Step 1 onwards, which is what makes `.queues` reachable.
