# UpstreamGate Interface Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `TaskRepository` injection and `UPSTREAM_TASK_ID` reading in pipeline tasks with a one-method `UpstreamGate` interface, with zero behaviour change.

**Architecture:** A new `UpstreamGate` interface (`mayGrow()`) plus a Guice-singleton `UpstreamGate.Factory` that wraps `TaskRepository` and reads `UPSTREAM_TASK_ID` from the task view's args. `PipelineTask` stores a gate and exposes a no-arg `drained()`. The five consumer tasks swap their `TaskRepository` constructor parameter for the factory. Producers keep an unchanged constructor via an overload.

**Tech Stack:** Java 17, Maven, Guice (assisted inject), JUnit 4 + fest-assert + Mockito.

**Spec:** `docs/superpowers/specs/2026-07-30-upstream-gate-interface-design.md`

## Global Constraints

- Branch: `refactor/remove-poison-pill`, new commits on top, no history rewrite, no force-push.
- Zero behaviour change: all failure modes (absent id, `UnknownTask`, `IOException`) collapse to `mayGrow() == false`, i.e. stop on first empty poll.
- No launcher (`CliApp`, `TaskResource`) or worker behaviour change; the only permitted launcher edit is re-pointing the `UPSTREAM_TASK_ID` constant reference from `PipelineTask` to `UpstreamGate`. `CliAppTest` and `TaskResourceTest` must pass unmodified (except the same constant re-import if they reference it).
- Commits: use the `/commit` skill; semantic-tag messages; never credit Claude/Anthropic; never commit `docs/superpowers/**`.
- Test command (DB-free devenv build; replace `<ClassName>`):
  `mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=<ClassName> test`
- `IndexTaskIntTest` hangs in this devenv: never include it in a local run; it runs in CI.
- Style: no em dash in any text written.

---

### Task 1: `UpstreamGate` interface and `Factory`

**Files:**
- Create: `datashare-app/src/main/java/org/icij/datashare/tasks/UpstreamGate.java`
- Create: `datashare-app/src/test/java/org/icij/datashare/tasks/UpstreamGateFactoryTest.java`

**Interfaces:**
- Consumes: `org.icij.datashare.asynctasks.TaskRepository#getTask(String)` (throws `IOException, UnknownTask`), `org.icij.datashare.asynctasks.Task` (public fields `id`, `args: Map<String, Object>`, method `getState()`, `Task.State#isFinal()`).
- Produces: `UpstreamGate` with `boolean mayGrow()`, constant `UpstreamGate.NONE`, static String `UpstreamGate.UPSTREAM_TASK_ID = "upstreamTaskId"`, and `UpstreamGate.Factory` with `@Inject Factory(TaskRepository)` and `UpstreamGate forTask(Task<?> taskView)`. Task 2 depends on all of these names exactly.

- [ ] **Step 1: Write the failing unit test**

```java
package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.asynctasks.TaskResult;
import org.icij.datashare.user.User;
import org.junit.Test;

import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.tasks.UpstreamGate.UPSTREAM_TASK_ID;

public class UpstreamGateFactoryTest {
    private final TaskRepositoryMemory taskRepository = new TaskRepositoryMemory();
    private final UpstreamGate.Factory factory = new UpstreamGate.Factory(taskRepository);

    @Test
    public void test_no_upstream_id_returns_the_none_gate() {
        Task<Long> taskView = new Task<>(ExtractNlpTask.class.getName(), User.local(), Map.of());

        assertThat(factory.forTask(taskView)).isSameAs(UpstreamGate.NONE);
        assertThat(UpstreamGate.NONE.mayGrow()).isFalse();
    }

    @Test
    public void test_running_upstream_may_grow() throws Exception {
        Task<Long> upstream = new Task<>(EnqueueFromIndexTask.class.getName(), User.local(), Map.of());
        upstream.setState(Task.State.RUNNING);
        taskRepository.insert(upstream, null);

        assertThat(gateOn(upstream.id).mayGrow()).isTrue();
    }

    @Test
    public void test_terminal_upstream_does_not_grow() throws Exception {
        Task<Long> upstream = new Task<>(EnqueueFromIndexTask.class.getName(), User.local(), Map.of());
        upstream.setState(Task.State.RUNNING);
        taskRepository.insert(upstream, null);
        upstream.setResult(new TaskResult<>(0L));

        assertThat(gateOn(upstream.id).mayGrow()).isFalse();
    }

    @Test
    public void test_unknown_upstream_does_not_grow() {
        assertThat(gateOn("unknown-task-id").mayGrow()).isFalse();
    }

    private UpstreamGate gateOn(String upstreamTaskId) {
        return factory.forTask(new Task<>(ExtractNlpTask.class.getName(), User.local(),
                Map.of(UPSTREAM_TASK_ID, upstreamTaskId)));
    }
}
```

Note: `UpstreamGate.UPSTREAM_TASK_ID` does not exist yet (it currently lives in `PipelineTask`); the test imports the new location on purpose. If `upstream.setResult(...)` does not flip the state to a final one with `TaskRepositoryMemory` (check how `UpstreamGateTest.runningUpstreamTask` does it, `datashare-app/src/test/java/org/icij/datashare/tasks/UpstreamGateTest.java:105-110`), mirror that test's exact state-setting mechanics.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UpstreamGateFactoryTest test`
Expected: COMPILATION ERROR, `UpstreamGate` does not exist.

- [ ] **Step 3: Implement `UpstreamGate`**

```java
package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepository;
import org.icij.datashare.asynctasks.UnknownTask;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Answers the only question a consumer stage may ask about its surroundings: can my input queue
 * still receive entries? It hides both the task repository and the upstream task id from the
 * tasks, which stay standalone pieces of code.
 */
public interface UpstreamGate {
    /**
     * Task-arg key carrying the id of the task whose output feeds this stage's input queue. Set by
     * the launcher (CliApp.runPipeline, TaskResource), never by an operator: it is not a CLI option.
     */
    String UPSTREAM_TASK_ID = "upstreamTaskId";

    /** True while the producer feeding this stage's input queue may still enqueue. */
    boolean mayGrow();

    /** No producer to wait for: the queue never grows, so a drain stops on its first empty poll. */
    UpstreamGate NONE = () -> false;

    @Singleton
    class Factory {
        private final TaskRepository taskRepository;

        @Inject
        public Factory(TaskRepository taskRepository) {
            this.taskRepository = taskRepository;
        }

        /**
         * The gate for this task view: repository-backed when the launcher set an upstream id in
         * the args, {@link #NONE} otherwise. An unknown task or a repository failure means "won't
         * grow", so the consumer falls back to stopping on an empty queue, the pre-gate behaviour.
         */
        public UpstreamGate forTask(Task<?> taskView) {
            String upstreamTaskId = (String) taskView.args.get(UPSTREAM_TASK_ID);
            if (upstreamTaskId == null) {
                return NONE;
            }
            return () -> {
                try {
                    return !taskRepository.getTask(upstreamTaskId).getState().isFinal();
                } catch (UnknownTask | IOException e) {
                    LoggerFactory.getLogger(Factory.class).warn("cannot read upstream task {} state, treating it as finished", upstreamTaskId, e);
                    return false;
                }
            };
        }
    }
}
```

Do NOT touch `PipelineTask` yet: `PipelineTask.UPSTREAM_TASK_ID` still exists in this task, duplicated on purpose (same string value), and disappears in Task 2.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UpstreamGateFactoryTest test`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit with the `/commit` skill**

Suggested message: `feat(tasks): add an UpstreamGate hiding the repository and upstream id from tasks`
Stage only `UpstreamGate.java` and `UpstreamGateFactoryTest.java`. Never stage `docs/superpowers/**`.

---

### Task 2: Migrate `PipelineTask` and the five consumer tasks to the gate

This task is compile-atomic: removing `drained(TaskRepository)` breaks every consumer at once, so main code and tests move in one commit.

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/PipelineTask.java`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/IndexTask.java:68,104-106`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/ExtractNlpTask.java:52-68,87`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/ArtifactTask.java:61-74,163,205-210`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/CategorizeTask.java:47-55,75`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/DeduplicateTask.java:31-38,68`
- Test (adapt): `UpstreamGateTest.java`, `IndexTaskTest.java`, `IndexTaskIntTest.java`, `ExtractNlpTaskTest.java`, `ExtractNlpTaskIntTest.java`, `ArtifactTaskTest.java`, `ArtifactChaosIntTest.java`, `ArtifactCoverageIntTest.java`, `CategorizeTaskIntTest.java`, `DeduplicateTaskTest.java`, `TaskWorkerLoopForPipelineTasksTest.java` (all under `datashare-app/src/test/java/org/icij/datashare/tasks/`)

**Interfaces:**
- Consumes: `UpstreamGate`, `UpstreamGate.NONE`, `UpstreamGate.UPSTREAM_TASK_ID`, `UpstreamGate.Factory#forTask(Task<?>)` from Task 1.
- Produces: `PipelineTask` constructors `PipelineTask(Stage, User, DocumentCollectionFactory<T>, PropertiesProvider, Class<T>)` (delegates with `UpstreamGate.NONE`; producers keep it) and `PipelineTask(Stage, User, DocumentCollectionFactory<T>, PropertiesProvider, Class<T>, UpstreamGate)`; `protected boolean drained()`; protected field `gate`. New public constructor signatures of the five consumers, each with `final UpstreamGate.Factory gateFactory` in place of `final TaskRepository taskRepository` (same position).

- [ ] **Step 1: Rewire `PipelineTask` to the gate**

In `PipelineTask.java`:
- Delete the `UPSTREAM_TASK_ID` constant (moved to `UpstreamGate` in Task 1) and the now-unused imports `TaskRepository`, `UnknownTask`, `IOException`, `Optional`, `java.util.Optional`-related static import if unused.
- Add field `protected final UpstreamGate gate;`.
- Replace the single constructor with the pair:

```java
    /** For producer stages: nothing feeds their input queue, the gate is {@link UpstreamGate#NONE}. */
    public PipelineTask(Stage stage, User user, DocumentCollectionFactory<T> factory, final PropertiesProvider propertiesProvider, Class<T> clazz) {
        this(stage, user, factory, propertiesProvider, clazz, UpstreamGate.NONE);
    }

    public PipelineTask(Stage stage, User user, DocumentCollectionFactory<T> factory, final PropertiesProvider propertiesProvider, Class<T> clazz, UpstreamGate gate) {
        this.propertiesProvider = propertiesProvider;
        this.stage = stage;
        this.user = user;
        this.factory = factory;
        this.gate = gate;
        this.inputQueue = getInputQueue(clazz);
        this.outputQueue = getOutputQueue(clazz);
    }
```

- Delete `upstreamTaskId()` and `upstreamRunning(TaskRepository)` entirely.
- Replace `drained(TaskRepository)` with (keep the javadoc, updated to name the gate):

```java
    /**
     * True when a drain that just polled an empty queue may stop: the producer feeding it is done
     * and the queue is still empty. The gate read comes first on purpose, so an entry enqueued
     * between the caller's poll and that read is caught by the emptiness check instead of being
     * stranded. With no upstream this is just "the queue is empty", the pre-gate behaviour: stop
     * on the first empty poll.
     */
    protected boolean drained() {
        return !gate.mayGrow() && inputQueue.isEmpty();
    }
```

- [ ] **Step 2: Migrate the five consumer constructors and call sites**

Same mechanical change in each class: replace the constructor parameter `final TaskRepository taskRepository` with `final UpstreamGate.Factory gateFactory` (same position), delete the `taskRepository` field and its assignment, pass `gateFactory.forTask(taskView)` to `super(...)`, replace `drained(taskRepository)` with `drained()`, drop the `TaskRepository` import, add nothing else.

`ExtractNlpTask.java` (both constructors; the package-private one takes a plain `UpstreamGate` so tests can stub it):

```java
    @Inject
    public ExtractNlpTask(Indexer indexer, PipelineRegistry registry, final DocumentCollectionFactory<String> factory, final UpstreamGate.Factory gateFactory, @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> progressCallback) {
        this(indexer, registry.get(Pipeline.Type.parse((String)taskView.args.get(NLP_PIPELINE_OPT))), factory, gateFactory.forTask(taskView), taskView, progressCallback);
    }

    ExtractNlpTask(Indexer indexer, Pipeline pipeline, final DocumentCollectionFactory<String> factory, final UpstreamGate gate, @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> progressCallback) {
        super(Stage.NLP, taskView.getUser(), factory, new PropertiesProvider(taskView.args), String.class, gate);
        ...unchanged body, minus `this.taskRepository = taskRepository;`...
    }
```

and at line 87: `if (drained(taskRepository)) {` becomes `if (drained()) {`.

`IndexTask.java` (no field to delete; it used the parameter inline):

```java
    @Inject
    public IndexTask(final ElasticsearchSpewer spewer, final DocumentCollectionFactory<Path> factory, final UpstreamGate.Factory gateFactory, @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> progressCallback) throws IOException {
        super(Stage.INDEX, taskView.getUser(), factory, new PropertiesProvider(taskView.args), Path.class, gateFactory.forTask(taskView));
```

and lines 102-106 become:

```java
        // The drainer has no notion of an upstream stage: without a latch it stops on its first
        // empty poll, which is only right when the producer has already finished.
        if (gate != UpstreamGate.NONE) {
            drainer.setLatch(new UpstreamSealableLatch(this::drained, UPSTREAM_POLL_INTERVAL_MS));
        }
```

`ArtifactTask.java`: same parameter swap, `super(..., String.class, gateFactory.forTask(taskView))`, delete the `taskRepository` AND `taskArgs` fields plus the whole `upstreamTaskId()` override (lines 203-210, including its javadoc), `drained(taskRepository)` at line 163 becomes `drained()`. Keep `propertiesProvider`-based config reads untouched. Delete the now-unused `Map` import if it was only used for `taskArgs` (check other uses first).

`CategorizeTask.java` and `DeduplicateTask.java`: same parameter swap, `super(...)` gains `gateFactory.forTask(taskView)` as last argument, field deletion, `drained()` at former call sites (CategorizeTask.java:75, DeduplicateTask.java:68).

Launcher constant follow-through: `CliApp.java` and `TaskResource.java` reference `PipelineTask.UPSTREAM_TASK_ID`, which no longer exists. Re-point those references (import or qualifier) to `UpstreamGate.UPSTREAM_TASK_ID`. Same string value, zero behaviour change; touch nothing else in these files. If `CliAppTest`/`TaskResourceTest` reference the constant, apply the same one-line re-import there.

- [ ] **Step 3: Verify main code compiles**

Run: `mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true compile`
Expected: BUILD SUCCESS. Then confirm the decoupling is real:

Run: `grep -rl "TaskRepository" datashare-app/src/main/java/org/icij/datashare/tasks/ --include="*Task.java"`
Expected: no output (the only tasks-package file naming `TaskRepository` is `UpstreamGate.java`).

- [ ] **Step 4: Adapt the tests**

Find every broken construction site: `grep -rn "taskRepository\|TaskRepository" datashare-app/src/test/java/org/icij/datashare/tasks/`. Only files constructing one of the five consumers need edits; `TaskFinderTest`, `BatchSearchRunner*`, `CreateNlpBatchesFromIndex*`, `TaskManagerMemoryForBatchSearchTest` use the repository for other purposes and must not change. Two substitution patterns:

1. Tests that exercise the gate behaviour (`UpstreamGateTest`, and the int tests wiring a real repository): wrap the existing repository, e.g. in `UpstreamGateTest.nlpTaskGatedOn`:

```java
    private ExtractNlpTask nlpTaskGatedOn(Task<Long> upstream) {
        Map<String, Object> args = Map.of(UPSTREAM_TASK_ID, upstream.id);
        return new ExtractNlpTask(indexer, pipeline, factory,
                new UpstreamGate.Factory(taskRepository).forTask(new Task<>(ExtractNlpTask.class.getName(), User.local(), args)),
                new Task<>(ExtractNlpTask.class.getName(), User.local(), args), progress -> null);
    }
```

   and its import switches from `org.icij.datashare.tasks.PipelineTask.UPSTREAM_TASK_ID` to `org.icij.datashare.tasks.UpstreamGate.UPSTREAM_TASK_ID`. The three test methods' bodies and assertions stay byte-for-byte identical (spec requirement: the concurrent producer/consumer coverage keeps asserting the same things).

2. Tests that only needed a repository because the constructor demanded one: pass a stub gate instead and delete the fake repository wiring. For constructors taking the factory (not the gate), pass `new UpstreamGate.Factory(taskRepository)` where a repository already exists, or construct the task view without `UPSTREAM_TASK_ID` and pass a factory over an empty `TaskRepositoryMemory` (the gate resolves to `NONE`, pre-gate behaviour). Where the package-private `ExtractNlpTask` constructor is used, `UpstreamGate.NONE` or a lambda (`() -> true` / `() -> false`) replaces the repository argument directly.

Keep each test's assertions unchanged; only construction wiring may move.

- [ ] **Step 5: Run the adapted tests**

Run: `mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest='UpstreamGateTest,UpstreamGateFactoryTest,IndexTaskTest,ExtractNlpTaskTest,ExtractNlpTaskIntTest,ArtifactTaskTest,ArtifactChaosIntTest,ArtifactCoverageIntTest,CategorizeTaskIntTest,DeduplicateTaskTest,TaskWorkerLoopForPipelineTasksTest' test`
Expected: PASS. (`IndexTaskIntTest` deliberately excluded: it hangs in this devenv and runs in CI.)

- [ ] **Step 6: Run the untouched launcher tests as a regression check**

Run: `mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest='CliAppTest,TaskResourceTest,PipelineHelperTest' test`
Expected: PASS with zero modifications to these files (`git status` must not list them).

- [ ] **Step 7: Commit with the `/commit` skill**

Suggested message: `refactor(tasks): inject an UpstreamGate instead of the TaskRepository in pipeline tasks`
Stage the five task files, `PipelineTask.java`, and the adapted test files only.

---

### Task 3: Reword the stale CommonMode warning

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/mode/CommonMode.java:130-132`

**Interfaces:**
- Consumes: nothing from earlier tasks (log text only).
- Produces: nothing relied upon later.

- [ ] **Step 1: Replace the warning text**

The current text claims CLI runs are sequenced by `CliApp.runPipeline`, which is false since stages overlap under the gate. Replace:

```java
        if (getTaskWorkersNb() > 1) {
            logger.warn("taskWorkers is set to {}: pipeline stages can then run concurrently, so a consumer stage may exit as soon as its input queue looks empty and strand documents. CLI runs are sequenced by CliApp.runPipeline and are unaffected.", getTaskWorkersNb());
        }
```

with:

```java
        if (getTaskWorkersNb() > 1) {
            logger.info("taskWorkers is set to {}: pipeline stages can run concurrently. Consumer stages launched with an upstream task id keep draining until their producer is done; a stage started against a queue with no upstream stops on its first empty poll.", getTaskWorkersNb());
        }
```

(WARN downgraded to INFO: with the gate this is normal operation, not a hazard.)

- [ ] **Step 2: Compile**

Run: `mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit with the `/commit` skill**

Suggested message: `docs(mode): stop claiming CLI stages are sequenced in the taskWorkers warning`

---

### Task 4: Acceptance sweep

**Files:** none created or modified; verification only.

- [ ] **Step 1: Acceptance greps from the spec**

Run: `grep -rln "TaskRepository" datashare-app/src/main/java/org/icij/datashare/tasks/`
Expected: exactly one line, `.../tasks/UpstreamGate.java`.

Run: `grep -rn "UPSTREAM_TASK_ID" datashare-app/src/main/java/`
Expected: definition in `UpstreamGate.java`, launcher usages in `CliApp.java` and `TaskResource.java` importing it from `UpstreamGate` (done in Task 2), and no remaining reference to `PipelineTask.UPSTREAM_TASK_ID` anywhere.

- [ ] **Step 2: Full task-package test run**

Run: `mvn -q -o -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest='org.icij.datashare.tasks.*Test,CliAppTest,TaskResourceTest' test`
Expected: PASS (again minus `IndexTaskIntTest`; if the pattern pulls it in, exclude with `-Dtest='...,!IndexTaskIntTest'`).

- [ ] **Step 3: Report**

Summarize the diff for Pierre so he can check CI before pushing (per his rule: ask him to verify tests/lint before pushing; do not push).
