# Stage processors: one implementation per stage, two executors

## Goal

Each processing step (language detection, NER, heavy NLP / Data Science) is written once.
It runs on a small machine with the Memory, Redis or AMQP transports, and on a cloud cluster
through Temporal, benefiting from its activity dispatching. Temporal stays optional.

## Problem

- `IndexTask` and `IndexActivityImpl` already duplicate the index logic. A Language activity
  would be a third copy.
- Scan→Index is orchestrated twice: by `CliApp.runPipeline` (queues + `UpstreamGate`) and by
  `IndexWorkflowImpl` (Temporal signal `onPaths` + async activities).
- Per-item logic is entangled with queue-executor machinery. In `LanguageDetectTask`,
  `detectLanguage(entry, counters)` is the work; `drainQueue`, `UpstreamGate`, the worker pool
  and the counters are executor.

## Existing building blocks (kept)

- `Stage` enum (`datashare-api`): the identity of a stage, its order and the `Payload` it
  consumes (`NONE`, `PATH`, `ID`).
- `PipelineHelper`: `--stages` → sorted, validated `List<Stage>`; also queue naming.
- `CliApp.runPipeline`: starts one task per stage, chained by `UpstreamGate.UPSTREAM_TASK_ID`.
- `@TemporalSingleActivityWorkflow` + `TemporalWorkflowGenerator`: wraps a `Task` as a
  one-activity workflow. Single-stage runs keep using it.
- `TaskManager` transports, bound in `CommonMode`.

Not to be confused: `text.nlp.Pipeline` and `extension.PipelineRegistry` are the NLP engine
abstraction (CoreNLP and similar), unrelated to this design.

## Design

### 1. Stage contract

`Stage` stays the single identity. One field is added next to `consumed`: the `Payload` a stage
**produces**. "Can A feed B" is `A.produced == B.consumed`, checked when a pipeline is declared,
alongside the existing alternatives rules in `PipelineHelper`. It replaces the ad-hoc
`getDefaultNextStage` logic.

The behavior is a batch function bound to a `Stage`:

```java
interface StageProcessor<I, O> {
    Stage stage();
    StageResult process(List<I> batch, StageContext ctx);
}
```

- No queue, gate or Temporal type appears in the contract.
- `StageContext` carries options, the project, cancellation (`isCancelled()`) and the output
  channel `emit(List<O>)`.
- `StageResult` holds only the counters (updated, unchanged, skipped, failed).
- Items are small and serializable (`DocReference`, `Path`).
- A registry maps `Stage` → `StageProcessor`. It lives outside `datashare-api` (the enum cannot
  see app classes) and is not `PipelineRegistry`.
- Per-stage Temporal settings (task queue, timeout) are metadata on the processor (annotation or
  method), not extra classes. A heavy NER stage can target its own task queue (e.g. GPU workers).

**Source stages (SCAN).** Output goes through `ctx.emit`, not the return value, so a source stage
is an ordinary processor called with an empty batch that emits as it walks. Non-source stages use
the same mechanism, so there is a single contract.

**Idempotence.** `process(batch)` must be safe to re-run: Temporal retries depend on it.

### 2. Executors

One interface, `PipelineExecutor`: runs a `List<Stage>` plus args and reports whether every
stage completed. One implementation is bound per JVM at startup in `CommonMode`, next to the
`TaskManager` binding: `TemporalPipelineExecutor` in the Temporal branch,
`QueuePipelineExecutor` in all others. The CLI and `TaskResource` inject `PipelineExecutor`.
There is no runtime switching and the two never run together in one deployment.

| | How many | Role |
|---|---|---|
| `StageProcessor` (`LanguageStage`, `IndexStage`, `NerStage`, …) | one per stage | All the real logic |
| `StageTask` | one, generic | Polls the input queue in batches, calls `process`, `emit` pushes to the next queue, handles cancellation and the `UpstreamGate` |
| `StageActivity` | one, generic | Receives `(Stage, batch, args)`, looks up the processor, calls `process`; `emit` signals the workflow |
| `PipelineWorkflow` | one, generic | Forwards each emitted batch asynchronously to the next stage's activity; awaits all promises |

- **Queue executor:** today's `runPipeline`, with `StageTask` replacing the per-stage task
  classes' drain/worker/counter code (currently in `PipelineTask`).
- **Temporal executor:** a single generic workflow, not one per pipeline. It generalizes what
  `IndexWorkflowImpl` hardwires for Scan→Index.
- Termination and backpressure stay inside each executor (gate vs promises). They are not unified.
- Unchanged: `startTask(SomeTask.class)`, `@TemporalSingleActivityWorkflow`, the `Task` API.
- Declaration: still a linear `List<Stage>`. No DAG, no DSL.

### 3. Migration (each step ships alone, existing tests stay green)

1. **Language:** `LanguageStage`, generic `StageTask`, queue executor. `LanguageDetectTask`
   delegates to the stage.
2. **Index:** `IndexTask` and `IndexActivityImpl` both call `IndexStage`.
3. **Scan, then generic workflow:** the `emit` contract, `PipelineWorkflow` and `StageActivity`
   replace `IndexWorkflowImpl`.
4. **NER/NLP,** then heavy Data Science stages, one at a time.

### 4. Errors and cancellation

- Per-document failure: counted in `StageResult`, never thrown (current behavior).
- Infrastructure failure: thrown. The queue executor fails the task (as `WorkersFailed` does);
  Temporal retries the activity.
- Cancellation: `ctx.isCancelled()` covers interrupt-based cancel and the Temporal heartbeat.

### 5. Testing

- One contract test per stage, run through both adapters: `StageTask` with memory queues,
  `StageActivity` in the existing Temporal test rule. Same output and counters expected.
- Existing `LanguageDetectTask` and `IndexTask` tests stay in place during the migration.

## Out of scope

DAG pipelines, running both executors in one deployment, changes to the `Task` API, renaming
anything to Temporal's `Namespace`.

## Open

- Name of the composition concept: `Pipeline` already means the NLP engine in `text.nlp`.
  `PipelineHelper` stays until this is decided.
- Where the `Stage` → `StageProcessor` registry lives (module and wiring).
