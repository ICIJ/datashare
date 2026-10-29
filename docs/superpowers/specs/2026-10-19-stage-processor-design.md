# Stage processors: one implementation per stage, two executors

## Goal

Each processing step (language detection, NER, heavy NLP / Data Science) is written once.
It runs on a small machine with the Memory, Redis or AMQP transports, and on a cloud cluster
through Temporal, benefiting from its activity dispatching. Temporal stays optional.

Three aims, in this order of priority when they conflict: fewer lines and classes so that the
team can maintain it; no duplicated stage definition between the queues and Temporal (INDEX is
the hardest and the first target); every transport still works, Temporal being heavy and
optional.

## Problem (starting point)

- `IndexTask` and `IndexActivityImpl` duplicated the index logic. A Language activity would
  have been a third copy.
- Scan→Index was orchestrated twice: by `CliApp.runPipeline` (queues + `UpstreamGate`) and by
  `IndexWorkflowImpl` (Temporal signal `onPaths` + async activities).
- Per-item logic was entangled with queue-executor machinery. In `LanguageDetectTask`,
  `detectLanguage(entry, counters)` is the work; `drainQueue`, `UpstreamGate`, the worker pool
  and the counters are executor.

## Existing building blocks (kept)

- `Stage` enum (`datashare-api`): the identity of a stage, its order and the `Payload` it
  consumes (`NONE`, `PATH`, `ID`).
- `PipelineHelper`: `--stages` → sorted, validated `List<Stage>`; also queue naming.
- `UpstreamGate.UPSTREAM_TASK_ID`: how the queue executor chains a stage to the task before it.
- `@TemporalSingleActivityWorkflow` + `TemporalWorkflowGenerator`: wraps a `Task` as a
  one-activity workflow. Single-stage runs and the one-shot jobs keep using it.
- `TaskManager` transports, bound in `CommonMode`.

Not to be confused: `text.nlp.Pipeline` and `extension.PipelineRegistry` are the NLP engine
abstraction (CoreNLP and similar), unrelated to this design.

## Design

### 1. Stage contract

`Stage` stays the single identity. The behavior is a batch function bound to a `Stage`:

```java
interface StageProcessor<I, O> {
    Stage stage();
    Class<I> itemType();            // to read a batch received as JSON
    StageResult process(List<I> batch, StageContext<O> ctx);
}

interface StageContext<O> extends AutoCloseable {
    Project project();
    Map<String, Object> options();
    boolean isCancelled();
    void emit(List<O> out);
    <R> R resource(Class<R> type, Supplier<R> factory);
    void close();
}
```

- No queue, gate or Temporal type appears in the contract.
- `options()` are the properties of the run, as the queue executor gives them to a stage. A
  stage reads its typed options from them (`PropertiesProvider.toRecord`, as `IndexOptions` and
  `LanguageOptions` do).
- `StageResult` holds only the counters (updated, unchanged, skipped, failed).
- Items are small and serializable (`DocReference` queue entries, `Path`).
- **Per-run state is a context resource.** What depends on the run (the options record, the
  extractor, the spewer and the index it writes to) is built on first use through
  `ctx.resource(type, factory)` and closed with the context, in reverse order of creation. The
  processor itself holds only what lives for the process (the indexer, the language guesser).
  A queue task has one context per run; a Temporal activity has one per batch. The base class
  is `ScopedStageContext`.
- A registry (`StageRegistry`) maps `Stage` → `StageProcessor`. It lives outside
  `datashare-api` (the enum cannot see app classes) and is not `PipelineRegistry`.
- Not done: a `produced` payload on `Stage` to check that a stage can feed the next one when a
  pipeline is declared (`A.produced == B.consumed`), which would replace the ad-hoc
  `getDefaultNextStage` logic. Per-stage Temporal settings (task queue, timeout) as metadata on
  the processor are not done either: the generic activity has one fixed timeout today.

**Source stages (SCAN).** Output goes through `ctx.emit`, not the return value, so a source stage
is an ordinary processor called with an empty batch that emits as it walks. Non-source stages use
the same mechanism, so there is a single contract. `ScanStage` emits batches of 1000 paths.

**Idempotence.** `process(batch)` must be safe to re-run: Temporal retries depend on it.

### 2. Executors

One interface, `PipelineExecutor`: runs a `List<Stage>` plus args and reports whether every
stage completed. One implementation is bound per JVM at startup in `CommonMode`, next to the
`TaskManager` binding: `TemporalPipelineExecutor` when the queue type is Temporal,
`QueuePipelineExecutor` in all others. The CLI injects `PipelineExecutor` (`TaskResource` does
not use it yet). There is no runtime switching and the two never run together in one
deployment.

| | How many | Role |
|---|---|---|
| `StageProcessor` (`ScanStage`, `IndexStage`, `LanguageStage`, …) | one per stage | All the real logic |
| `StageTask` (today, replaced by the page task below) | one, generic | Polls the input queue, calls `process`, `emit` pushes to the next stage's queue, handles cancellation and the `UpstreamGate`. `IndexTask` and `LanguageDetectTask` are thin subclasses that keep their names for `startTask`, `@TemporalSingleActivityWorkflow` and the option annotations |
| `StageActivity` | one, generic | Receives `(Stage, options, batch)`, looks up the processor, calls `process`; `emit` signals the workflow |
| `PipelineWorkflow` | one, generic | Starts the first stage with an empty batch, forwards each emitted batch asynchronously to the next stage's activity, awaits all promises |

- **Queue executor (today):** starts one task per stage with `TaskManager.startTask`, chained by
  the upstream gate; the stage → task class map (`TASK_CLASSES`) is explicit. The target is
  described in "Queue executor, target: pages as tasks" below.
- **Temporal executor:** starts a single `PipelineWorkflow` (generic, not one per pipeline) and
  blocks until it ends.
- **Temporal workers.** The CLI never runs Temporal workers. A separate `--mode TASK_WORKER`
  process, on the same build and settings (same plugins, same data and artifact paths), registers
  the workflow and the activity. Without one the CLI waits forever. The workflow classes live in
  `org.icij.datashare.asynctasks.pipeline`, outside the packages that workflow discovery scans
  (`org.icij.datashare.tasks`, `org.icij.datashare.asynctasks.temporal`), whose naming
  conventions they do not follow; `TemporalDiscoveryTest` guards this.
- Termination and backpressure stay inside each executor (gate vs promises). They are not unified.
- Unchanged: `startTask(SomeTask.class)`, `@TemporalSingleActivityWorkflow`, the `Task` API.
- Declaration: still a linear `List<Stage>`. No DAG, no DSL.

#### Queue executor, target: pages as tasks

*Target state, not implemented.* Today a queue stage is one long task with its own loop over a
document queue (`PipelineTask`, `StageTask`, `UpstreamGate`). The target works like Temporal:
the unit of work is a page (a batch of items) and no loop, document queue or gate exists in the
pipeline path.

- **A generic page task** runs `process(stage, page)` and ends. The page is in the task
  arguments. One class replaces the per-stage task infrastructure on this path; `IndexTask`,
  `LanguageDetectTask` and the others stay as thin shells for the web path (`startTask`, the
  annotations), running one page through the same code.
- **`emit` starts the next stage's page tasks** through `TaskManager.startTask`, as
  `CreateNlpBatchesFromIndex` already does for NLP batches (a task that injects the
  `TaskManager` and starts tasks with the documents in their arguments). SCAN, a source, starts
  page tasks as it walks.
- **Termination.** Each task carries a run tag. The executor is finished when no task of the
  run is non-final. A task starts its children before it ends, so "every task of the run is
  final" cannot hold while pages are still to be started: the same ordering argument as an emit
  signal landing before the completion of the activity that sent it.
- **`TaskWorkerLoop` is the only loop.** Capacity is the number of task workers: `--taskWorkers`
  in an in-process run, `TASK_WORKER` processes (each with its slots) on Redis or AMQP. A page
  goes to whichever worker is free, on any machine.
- **Page size.** A consumer declares a preferred batch size and the emitting side re-chunks to
  it (Temporal too). A page of 1000 suits LANGUAGE; INDEX needs pages small enough that a
  multi-GB PST does not hold the other files hostage, and large enough to amortize the
  per-task setup (the context is built per task: extractor, spewer, index check). The balance
  is to be measured; if it cannot be found, a worker-level resource cache is needed.
- **Removed:** `PipelineTask` (pool, `runWorkers`, `drainQueue`, `drained()`), `StageTask`,
  `UpstreamGate` and its `UPSTREAM_TASK_ID` plumbing (including its three uses in
  `TaskResource`, which would use the executor instead), the loop copies in the other consumer
  tasks, the per-stage document queues and their naming, and the thread pool and concurrency of
  `ScopedStageContext`. The cancel `TODO` in `TaskWorkerLoop` (pipeline tasks not forwarding
  `requeue`) goes with them. `getDocument` and `causedByInterrupt` move to a utility.
- **Added:** the generic page task, the run tag, the executor's wait for the run, re-chunking.
- **Unchanged:** the Temporal side, `TaskWorkerLoop`, the one-shot jobs, the stage logic.
- **Costs:**
  - One task row per page with its arguments, hence a larger tasks table. The arguments are a
    `text` column (64 KB on MySQL, unlimited on PostgreSQL and SQLite; the supported databases
    are unchecked), and finished tasks are kept until purged (unchecked).
  - The document queues go from the pipeline path, and with them scaling by INDEX-only
    invocations on a shared queue and progress by queue size (progress would count pages).
    Anything else that reads or writes those queues would break; who does is unchecked.
  - Delivery is by the task transport (retries and requeue through `Task` retries, not verified
    end to end) instead of an unacknowledged poll.
- **Payload sizes.** Tasks are JSON in a Redis blocking queue (no limit in the code, 512 MB per
  value in Redis) or an AMQP message (RabbitMQ's default maximum is large, about 128 MB from
  memory); a page of 1000 paths is about 100 KB.
- **Alternatives considered.** (A) pages held in memory by the executor: simplest, with real
  backpressure, but single machine only. (C) document queues whose entries are lists: keeps
  manual scale-out and the gate but still needs a loop per consumer, a list element type in the
  extract queues and a task started per page to consume it, which makes the queue redundant.
  An executor-owned dispatcher loop polling the document queues: removes the task loops but
  adds a new loop.

### 3. Migration (each step ships alone, existing tests stay green)

1. **Language:** `LanguageStage`, generic `StageTask`, queue executor. Done.
2. **Index:** `IndexTask` and the Temporal side both run `IndexStage`; one `ElasticsearchSpewer`
   with an `onIndexed` listener replaces the abstract spewer, its two subclasses and its
   factory. Done.
3. **Scan, then generic workflow:** the `emit` contract, `PipelineWorkflow` and `StageActivity`
   replace `IndexWorkflowImpl`. Done.
4. **Temporal robustness (section 6):** emit batching, per-stage timeout, bounded retries and
   heartbeat. To do.
5. **The other stages as processors,** one at a time: NLP first (the easy one), then ARTIFACT
   (the test of the page-task design, see section 7), then CATEGORIZE, DEDUPLICATE and the
   index-reading sources. To do; ARTIFACT was tried on `StageTask` and reverted.
6. **Pages as tasks on the queues:** only after step 5 shows that NLP and ARTIFACT fit, and
   after measuring the per-page setup cost on INDEX. The generic page task, the run tag and the
   executor wait replace `PipelineTask`, `StageTask` and `UpstreamGate`, and `TaskResource` uses
   the executor. To do.

### 4. Errors and cancellation

- Per-document failure: counted in `StageResult`, never thrown (current behavior).
- Infrastructure failure: thrown. The queue executor fails the task (as `WorkersFailed` does);
  Temporal retries the activity.
- Cancellation: `ctx.isCancelled()` covers interrupt-based cancel and the Temporal heartbeat.

### 5. Testing

- One contract test per stage, run through both adapters: `StageTask` with memory queues,
  `StageActivity` in the existing Temporal test rule. Same output and counters expected.
- Existing `LanguageDetectTask` and `IndexTask` tests stay in place during the migration.
- Integration tests (`*IntTest`) are excluded from `mvn test` and run by name.

### 6. Parallelism

*Target state. The code still runs `--parallelism` threads inside a task, and the Temporal
activity still emits one signal per document.*

Corpora are terabytes and millions of documents, and one document can be huge (a multi-GB PST).
Two levels of parallelism exist today and they are not coordinated: threads inside a datashare
task (`--parallelism`) and threads inside the extract library (OCR pool, PST folder fan-out).
The strategy is one rule per level, and a bound on their product.

1. **Across documents: workers only.** The unit is a task (queue executors) or an activity
   (Temporal). A worker takes one document at a time; a document is never shared between workers.
   The scaling unit is the same in both systems: the number of worker processes, and slots in
   each, that consume a stage. It is chosen by the operator, not by one per-stage setting.
   A source stage (SCAN) runs once: its bottleneck is the filesystem, and it emits batches that
   the next stage fans out.
   - Queue executor: with pages as tasks (section 2), capacity is the number of task workers
     that run page tasks: `--taskWorkers` in an in-process run, and `TASK_WORKER` processes on
     as many machines as wanted with Redis or AMQP. `--parallelism` (threads inside a task)
     stops applying to the stages migrated to page tasks. SCAN runs as one task that starts
     page tasks as it walks.
   - Temporal executor: the capacity belongs to the workers. The CLI only submits the workflow
     and `--parallelism` does not apply. The number of INDEX activities in flight is bounded by
     the worker slots (`TASK_WORKER` processes and their max concurrent activities), and
     further batches wait in the task queue, which costs nothing because the activity timeout
     only starts when an activity starts. Scaling out means starting more workers. One task
     queue per stage, each with its own workers, is the Temporal way to give stages different
     capacity if that is ever needed; not needed to start.
   - There is deliberately no per-stage count (`INDEX=8,SCAN=1`): it fits a single invocation
     only, says nothing about other machines, and has no meaning on Temporal.
2. **Within a document: extraction threads.** The extract library keeps its own OCR pool and PST
   folder pool. OCR is coupled to the Tika parse (isolated parse context, embed buffers, the
   spew walk waits for each deferred OCR), so it is not a separate stage. This is the only
   in-process parallelism.
3. **Datashare does not run its own threads inside a task.** `StageTask` drops its worker pool
   (`runWorkers`, `--parallelism` threads sharing a context), and `ScopedStageContext` becomes
   single-threaded (a plain map and deque instead of the concurrent memoizing one). A machine's
   consumers used to be threads sharing one extractor; they are now page tasks with one
   extractor each, hence the budget below.
4. **The product is bounded by a per-worker-process thread budget** of about its CPUs. The budget
   is divided among the slots of the process (concurrent tasks or activities): each extractor
   gets `max(1, cpus / slots)` OCR and PST threads unless the user set them explicitly. Many
   slots with few threads suits many small documents; few slots with many threads suits PST-heavy
   corpora. A warning is logged when the configured product exceeds the CPU count. The division
   is computed on the worker where the extractor is built (`IndexStage`), never by the executor:
   on Temporal the CLI and the worker run on different machines.

Consequences to handle before running at scale on Temporal:

- One extractor per slot means one set of OCR/PST pools per slot, hence the division above.
  Whether N small pools perform as well as one large one is to be measured on a corpus with
  images and PSTs.
- A huge document holds its slot for hours. The other slots keep draining the queue, but the
  activity needs a per-stage timeout, a bounded retry policy and a heartbeat (the generic
  activity's one-minute timeout with unbounded retries is not usable for INDEX or ARTIFACT).
- Emitting one signal per document grows the workflow history (181 events for 40 documents
  measured; Temporal caps a history near 50k events). `emit` must be batched, with a flush
  after a successful `process`, and a final stage must not emit at all. A prototype took the
  40-document history from 181 to 21 events; it is not in the code.
- ARTIFACT is partly fused into INDEX: the raw artifact and the embed payload are recorded
  during the streaming parse, the other artifacts are produced by a second pass. The coupling
  goes through the artifact directory and manifest, which therefore must be shared by all
  INDEX and ARTIFACT workers.

**SCAN on Temporal is not redesigned.** It stays the generic stage: one activity that walks
and emits batches of 1000 paths by signal, one INDEX activity started per batch. Its known
limits stay as they are: a retried scan restarts from the beginning (INDEX is idempotent per
document), and it needs the per-stage timeout and heartbeat above like any long activity. A
resumable or paged scan was considered and left out of scope. On the queues SCAN is a task that
fills the shared queue.

**Scaling with the queues** must keep working without Temporal, and with pages as tasks it
works like Temporal: more `TASK_WORKER` processes, on as many machines as wanted, against a
shared Redis or AMQP task manager. What changes from today, where scaling meant launching
INDEX-only invocations that consume a shared document queue:

- **Shared resources.** The data directory, the artifact root and Elasticsearch are visible to
  every worker machine, as on Temporal. The memory task manager is single-process by nature.
- **No starting order.** There is no document queue to fill first: a page task exists only when
  its page does, and workers take whatever is ready.
- **`--taskWorkers` is the number.** In an in-process run the number of parallel pages is the
  number of task workers; `--parallelism` no longer multiplies it for migrated stages.
- **Delivery.** A page that fails goes through the task transport (`Task` retries, requeue)
  instead of being lost with an unacknowledged poll; the behavior end to end is to verify.
- **Aggregate view.** The tasks of a run share a tag, so counts and progress can be summed
  across machines from the task repository (progress counts pages, not documents).
- **Failures.** A failed SCAN leaves the pages it already started to run; the rerun of SCAN is
  manual, and INDEX is idempotent per document.

### 7. What counts as a stage

The task classes fall in three shapes and only the first belongs to this design.

- **Pipeline stages** drain per-document items and may emit to the next stage. Sources: SCAN,
  SCANIDX, SCANQUERY, ENQUEUEIDX. Consumers: INDEX, LANGUAGE, ARTIFACT, CATEGORIZE, NLP.
- **One-shot jobs** (batch search, batch download, batch NLP, NLP batch creation, structured
  entity extraction): one task is one unit of work with progress, cancel and a result. They
  already map one-to-one to a Temporal single-activity workflow and have no duplicated
  definition, so they stay as they are.
- **Trivial tasks** (API keys, admin policy): unchanged.

INDEX is the right first stage but does not cover everything the others need:

- NLP holds its heavy resource (the `Pipeline` and its models) in JVM-wide singletons, so a
  context per batch costs nothing extra; as a stage it would be about the size of
  `LanguageStage`. Whether the model cache is safe under several concurrent NLP workers in one
  JVM is to check.
- ARTIFACT is idempotent through its manifest and terminal, and its resources (source extractor,
  producer) are per worker. Its migration onto `StageTask` was about 2x slower because of
  per-document setup and was reverted; understand that before retrying (the setup must happen
  once per task).
- The source stages that read the index (cursors, paging) have a different resource problem from
  SCAN and are not covered yet.

## Out of scope

DAG pipelines, running both executors in one deployment, changes to the `Task` API, renaming
anything to Temporal's `Namespace`, redesigning the Temporal SCAN activity (cursor, paged
workflow), making OCR a separate stage (it is coupled to the parse, section 6), per-stage worker
counts on the command line, an executor-owned dispatcher loop over document queues (see the
alternatives in section 2).

## Open

- Name of the composition concept: `Pipeline` already means the NLP engine in `text.nlp`.
  `PipelineHelper` stays until this is decided.
- Whether the extraction threads are a second explicit setting or only derived from the CPU
  budget of the worker.
- Whether a huge document (a multi-GB PST) needs its own queue or stage settings.
- Whether structure and page artifacts can be produced during the INDEX streaming parse
  (an extract library question) instead of a second pass.
- `--reportName`: the thin `IndexTask` ignores it (it only warns), so the skip of already
  extracted documents and the re-queue of failures of the SCANIDX flow are gone. Either drop the
  option, or pass an optional reporter into `IndexStage` from the queue side only.
- Temporal with no worker running: the CLI waits forever. Check for a poller on the task queue
  at start and fail fast, and log the task queue name on both sides.
- `TemporalWorkflowGenerator` reads `call()` only on the annotated class, hence the delegating
  `call()` in `IndexTask` and `LanguageDetectTask`; reading inherited methods would remove them.
- Whether the Temporal SDK becomes an optional dependency (separate module): it is a hard
  compile dependency today.
- Pages as tasks (section 2): the page size for INDEX against the per-task setup cost; whether
  the `TaskManager` can be injected into a stage context in a `TASK_WORKER` process (it is in
  `CreateNlpBatchesFromIndex`); how a run's tasks are tagged and queried; when finished tasks
  are purged and what a task row costs in Redis and in the database; which other code reads the
  document queues; whether ARTIFACT, DEDUPLICATE and the index-reading sources fit a processor.
  If ARTIFACT does not fit, `PipelineTask` stays for it and most of the gain is lost.
