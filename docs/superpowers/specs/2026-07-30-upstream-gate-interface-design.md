# UpstreamGate interface refactor

Date: 2026-07-30
Branch: `refactor/remove-poison-pill` (new commits on top, no history rewrite)

## Problem

On this branch, every consumer pipeline task (`IndexTask`, `ExtractNlpTask`, `ArtifactTask`,
`CategorizeTask`, `DeduplicateTask`) injects `TaskRepository` and reads the launcher-set
`upstreamTaskId` from its args, only to answer one question: "may my input queue still receive
entries?". The PR reviewer flagged both as a code smell: tasks should be standalone pieces of code
that do not deal with their sibling tasks.

## Goal

Hide both the repository and the upstream task id behind a one-method gate interface. Tasks depend
only on the gate. Semantics are strictly unchanged: no behaviour change for launchers, operators,
or standalone stage runs.

Out of scope: the sealable-queue design (gate knowledge moved behind `DocumentQueue`, sealed by the
worker) is a follow-up PR, not this one. No launcher (`CliApp`, `TaskResource`) or worker change.

## Design

### New component: `UpstreamGate` (datashare-app, `org.icij.datashare.tasks`)

```java
public interface UpstreamGate {
    /** True while the producer feeding this stage's input queue may still enqueue. */
    boolean mayGrow();

    /** No producer to wait for: the queue never grows, pre-gate behaviour. */
    UpstreamGate NONE = () -> false;

    @Singleton
    class Factory {
        @Inject Factory(TaskRepository taskRepository) { ... }

        /** Reads UPSTREAM_TASK_ID from the task view's args; returns NONE when absent. */
        public UpstreamGate forTask(Task<?> taskView) { ... }
    }
}
```

- The `UPSTREAM_TASK_ID` constant and its javadoc (launcher-set, never an operator option) move
  from `PipelineTask` to `UpstreamGate`.
- The repository-backed gate: an upstream task in a final state or an `UnknownTask` means "won't
  grow"; an `IOException` or `RuntimeException` from the repository read means "may still grow"
  (see the amended Error handling section below).
- `Factory.forTask` reads the id from `taskView.args`, not from a `PropertiesProvider`. This is
  what lets `ArtifactTask` drop its `upstreamTaskId()` override (its provider holds app properties,
  not task args).

### `PipelineTask` changes

- Constructor gains an `UpstreamGate` parameter, stored in a field.
- `upstreamTaskId()` and `upstreamRunning(TaskRepository)` are deleted.
- `drained(TaskRepository)` becomes no-arg `drained()`: `!gate.mayGrow() && inputQueue.isEmpty()`,
  keeping the current read ordering (state before emptiness) and its comment: an entry enqueued
  between the caller's poll and the state read is caught by the emptiness check.
- `TaskRepository` import disappears from `PipelineTask` and all subclasses.

### Task constructor changes (the five consumers)

Each swaps `final TaskRepository taskRepository` for `final UpstreamGate.Factory gateFactory` and
passes `gateFactory.forTask(taskView)` to the `PipelineTask` constructor. Guice injects the factory
exactly as it injected the repository; no new binding is needed in `CommonMode` beyond what Guice
derives from `@Singleton`/`@Inject`.

- `IndexTask`: `drainer.setLatch(new UpstreamSealableLatch(this::drained, UPSTREAM_POLL_INTERVAL_MS))`.
  The current `upstreamTaskId().isPresent()` guard becomes the identity check
  `gate != UpstreamGate.NONE` (no extra helper method).
- `ExtractNlpTask`, `CategorizeTask`, `DeduplicateTask`: `drained(taskRepository)` becomes `drained()`.
- `ArtifactTask`: same, plus deletion of the `upstreamTaskId()` override.

Producer-only tasks (`ScanTask`, `EnqueueFromIndexTask`, `ScanIndexTask`) are untouched.

### Error handling

Amended 2026-07-30 after Pierre's upstream commit 50b7ed4f4: an absent id or an `UnknownTask` means
`mayGrow() == false` (no upstream to wait for, stop on first empty poll), but an `IOException` or a
`RuntimeException` from the repository read means `mayGrow() == true` (a read failure says nothing
about the producer; retrying costs one poll interval, while calling it finished can end the drain
on a transient error and strand later entries). `JooqTaskRepository` throws jOOQ's unchecked
`DataAccessException`, hence the `RuntimeException` catch.

### Also in scope

Reword the `taskWorkers > 1` warning in `CommonMode`: it still claims CLI runs are sequenced by
`CliApp.runPipeline`, which has been false since stages were made to overlap under the gate. The
new text must not promise sequencing and should point at the gate as what makes concurrent stages
terminate correctly.

## Testing (TDD)

- `UpstreamGateFactoryTest` is the unit test of the new type: absent id returns `NONE`; running
  upstream makes `mayGrow()` true; final state and `UnknownTask` make it false; `IOException` and
  unchecked `RuntimeException` make it true (amended semantics). Existing test cases are adapted,
  not dropped.
- Task tests (`IndexTaskTest`, `IndexTaskIntTest`, `ExtractNlpTaskTest`, `ExtractNlpTaskIntTest`,
  `ArtifactTaskTest`, `ArtifactChaosIntTest`, `ArtifactCoverageIntTest`, `CategorizeTaskIntTest`,
  `DeduplicateTaskTest`, `TaskWorkerLoopForPipelineTasksTest`) switch from wiring a repository to
  passing a `Factory` or a stub gate; a lambda (`() -> true` / `() -> false`) replaces fake
  repositories where only the gate behaviour matters.
- The concurrent producer/consumer coverage from commit 192dfc50f must still pass unchanged in
  what it asserts.
- `CliAppTest` and `TaskResourceTest` are untouched: launchers still set `UPSTREAM_TASK_ID` in the
  args and nothing else changes for them.
- Local run caveat: exclude `IndexTaskIntTest` from local runs (hangs in the devenv); it runs in CI.

## Acceptance

- No pipeline task class imports `TaskRepository` or reads `UPSTREAM_TASK_ID`.
- `git grep -l TaskRepository datashare-app/src/main/java/org/icij/datashare/tasks/` returns only
  `UpstreamGate.java`.
- All adapted tests pass; behaviour of CLI, server mode, and standalone stages is unchanged.
