# Remove the POISON pill mechanism from pipeline stages

Date: 2026-07-27
Status: approved, ready for planning

## Problem

Pipeline stages signal end-of-stream with an in-band sentinel: `PipelineTask.PATH_POISON`
(`Paths.get("POISON")`) and `PipelineTask.STRING_POISON` (`"POISON"`). One sentinel can only
terminate one consumer, so every stage that runs more than one consumer has to build something on
top of it. `ArtifactTask` is where that cost is visible: a re-offer relay so N pool workers each
see the pill, a residual sentinel left behind by that relay, a startup drain that must remove the
residue while preserving real doc refs, and a bounded null-poll fallback for the concurrent-producer
race. Roughly 100 lines of code and defensive comments for a one-token signal, and six of the
fifteen commits before this spec were fixes to that machinery.

Two facts about the current code make the sentinel mostly ceremony already:

1. `taskWorkers` defaults to `1` (`datashare-cli/.../DatashareCliOptions.java:271`). `CliApp` and
   `TaskResource` submit stage tasks to that single-threaded executor in stage order, so stages
   already run one after another in the default configuration.
2. `queuePoll` is set nowhere in datashare, so `DocumentQueueDrainer`'s poll timeout is
   `Duration.ZERO`, a non-blocking `poll()`. `IndexTask` already stops at the first empty poll. The
   pill only ever arrives as a slightly earlier stop.

## Goal

Consumers stop when their input queue is empty. No sentinel, no relay, no residue, no timing
heuristics, and correct for any number of consumers (in-process pool or separate worker processes).

## Approach

Move the end-of-stream guarantee from the data plane to the launcher. A consumer stage may assume
its input queue is complete when it starts, because the launcher awaits the producer stage's task
before starting the consumer stage's task. Then "empty" means "done", exactly, for every consumer.

Rejected alternatives:

- **Gate on upstream task state** (consumer exits when the queue is empty *and* the upstream task is
  terminal, read from the shared task repository). Keeps producer/consumer overlap and is exact, but
  adds a task-repository dependency to every consumer and requires wiring the producer's task id
  into every launch site. Recorded as the upgrade path if the lost overlap ever measures.
- **Let Temporal own the barrier.** Same semantics as the chosen approach, with sequencing in the
  workflow instead of in `CliApp`. Correct destination, but it lands with the Temporal migration.

## Design

### 1. Deletions

Remove `PATH_POISON` and `STRING_POISON` as termination signals, and everything built to compensate
for them.

| Site | Today | After |
| --- | --- | --- |
| `ScanTask:44`, `DeduplicateTask:59`, `CategorizeTask:88`, `EnqueueFromIndexTask:100` | emit a sentinel | nothing |
| `ElasticsearchSpewer.close():420` (datashare-index) | `outputQueue.put("POISON")` on the NLP queue, once per `DocumentConsumer.awaitTermination` call, so possibly several times per run | override deleted, parent `Spewer.close()` is empty |
| `DeduplicateTask.transferToOutputQueue` | `take()` until `PATH_POISON`, blocks forever if absent | `while ((p = poll()) != null)` |
| `IndexTask:95` | `drainer.drain(PATH_POISON)` | `drainer.drain()`, the same loop, stops on null |
| `ExtractNlpTask:80`, `CategorizeTask:66` | poison, or `NB_MAX_POLLS` empty polls | `while ((e = poll()) != null)` |
| `ArtifactTask:150-230` | poison relay, `drainStaleResidualPoison()`, null-poll budget | one plain worker loop, N workers, no coordination |

`NB_MAX_POLLS` and the polling-interval budgets go with them. CATEGORIZE stops spending up to
3 x 60s waiting out an empty queue on every run. `ArtifactTask`, `CategorizeTask` and
`ExtractNlpTask` stop reading `pollingInterval` altogether, since a complete queue leaves nothing to
wait for. The CLI option stays declared on both CLI surfaces so no settings file breaks.

extract-lib is not touched. `DocumentQueueDrainer.drain(Path poison)` stays for extract-cli;
datashare stops calling it. No dependency bump, no extract release needed.

### 2. The launcher barrier

`CliApp.runTaskWorker`'s nine `if (pipeline.has(...))` blocks become a loop over
`pipeline.stages`, which `PipelineHelper` already sorts by `Stage.comparator` (enum ordinal), which
is pipeline order:

```java
for (Stage stage : pipeline.stages) {
    String taskId = taskManager.startTask(TASK_CLASSES.get(stage), nullUser(), propertiesToMap(properties));
    taskManager.awaitTermination(MAX_VALUE, SECONDS, Set.of(taskId));
    // stage ERROR or CANCELLED: stop the pipeline, do not feed a downstream stage partial input
}
```

Keep it in `CliApp` as a package-private static `runPipeline(TaskManager, PipelineHelper,
Properties)`, which is enough to test it: `CliAppTest` already has a `TaskManagerMemory` plus
`TaskRepositoryMemory` harness. No new class, no new file.

`TASK_CLASSES` maps the nine stages `CliApp` launches today: SCAN, SCANIDX, DEDUPLICATE, INDEX,
ENQUEUEIDX, CATEGORIZE, CREATENLPBATCHESFROMIDX, NLP, ARTIFACT. `BATCHNLP` has no CLI mapping today
and keeps none; a configured stage with no mapping is logged and skipped.

A failed or cancelled stage stops the pipeline. Running a downstream stage over a partially filled
queue would now silently mean "the input was complete", which is exactly the failure the sentinel
used to produce.

Side effect: the current ordering bug disappears. `CliApp` submits DEDUPLICATE (line 180) before
SCAN (line 189), which with `taskWorkers=1` and the old blocking `take()` is a deadlock. Stage
order now comes from the enum.

### 3. Retro compatibility

Redis queue keys survive upgrades (`RedisDocumentQueue.close()` only closes the client), so a queue
can still hold a `"POISON"` entry written by a pre-upgrade run. Consumers skip it instead of
terminating on it, and instead of trying to index a file named POISON or resolve it as a doc ref.

The string-queue consumers (CATEGORIZE, NLP, ARTIFACT) need no code for this. A `"POISON"` entry
parses as `new DocReference("POISON", null)`, `indexer.get` returns null for it, and
`PipelineTask.warnIfNull` already logs "document <POISON> could not be retrieved ... skipping" and
moves on. That is exactly the wanted behaviour, already written.

So the only guard needed is in `IndexTask`, which would otherwise try to extract a file named
POISON. One line in `progressTrackConsumer`:

```java
// ponytail: transitional. Redis queue keys survive upgrades, so a pre-21.16 run can leave a
// "POISON" path behind. Remove once no live queue predates 21.16.
if (PATH_POISON.equals(path)) { logger.warn("skipping legacy POISON entry in queue {}", inputQueue.getName()); return; }
```

`PATH_POISON` therefore survives as a skip target only, no longer as a termination signal, and
`STRING_POISON` is deleted outright. The guard goes in the consumer, not in the drainer: passing the
sentinel to `drainer.drain(PATH_POISON)` would stop the drain there and strand every entry behind
it, which is the bug being removed.

Two accepted costs: `drain()` counts the skipped entry, so a legacy queue reports one extra unit in
the progress denominator, and a legacy sentinel in a string queue bumps ARTIFACT's `nbSkipped`, whose
end-of-run ERROR suggests re-running the stage. Both are one-off per queue, since the entry is
consumed. A real file named `POISON` at the data-dir root is also skipped; the WARN makes that
diagnosable.

### 4. Web endpoints

`TaskResource.indexPath` (scan, optional scanIndex, index) and `TaskResource.extractNlp` (enqueue,
NLP) are the two paired launch sites, both restricted to LOCAL and EMBEDDED modes. With the default
`taskWorkers=1` they already serialize FIFO in `CommonMode`'s single-threaded executor, which is the
same barrier `PipelineRunner` makes explicit, so they need no change.

Because that ordering now carries correctness weight, `CommonMode` logs a WARN at startup when
`taskWorkers > 1`: pipeline stages may overlap, and a consumer stage can exit as soon as its input
queue looks empty. This is a warning, not enforcement. Enforcement is the rejected upstream-task-state
option and stays out of scope.

## Testing

Updates:

- `ScanTaskTest`, `DeduplicateTaskTest`: queue-size assertions lose the `// with POISON` entry.
- `CategorizeTaskIntTest`: sizes, plus the tests that pre-seed `STRING_POISON` (`:141`, `:170`,
  `:201`, `:248`, `:257`).
- `IndexTaskIntTest:73`, `ExtractNlpTaskIntTest`, `EnqueueFromIndexTaskTest:102-105`.

Deletions:

- The `ArtifactTaskTest` residual-poison suite (`:404-519`), whose subject no longer exists.

New, two tests:

- `CliAppTest.runPipeline`: stage N+1 starts only after N is terminal, and a failed stage stops the
  pipeline.
- `IndexTaskIntTest`: a queue pre-seeded `[pathA, POISON, pathB]` indexes both files and logs the
  skip.

The "exits when the queue drains" case needs no new test: `CategorizeTaskIntTest`,
`ExtractNlpTaskIntTest` and `ArtifactTaskTest` (already parameterised on `parallelism`) all run their
loop to completion, and once the sentinel is gone that is the only way they can terminate.

## Risks

- **Lost producer/consumer overlap.** SCAN completes fully before INDEX starts, so a slow filesystem
  walk adds to wall clock instead of hiding behind extraction. That overlap is not reliable today
  because `queuePoll` is unset. If it ever measures, the upgrade path is the upstream-task-state
  gate, and the code comment says so.
- **Whole-stage buffering.** Redis holds a full stage's output. Already true for ENQUEUEIDX. Memory
  queues default to 1e6 capacity (`PropertiesProvider.DEFAULT_QUEUE_CAPACITY`).
- **A consumer stage started by hand against a live producer** exits early on an empty queue.
  Warned at startup, and no worse than today's `IndexTask` behaviour.
