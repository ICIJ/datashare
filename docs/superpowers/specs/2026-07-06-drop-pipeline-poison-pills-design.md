# Drop POISON pills from pipeline queues

Date: 2026-07-06
Branch: `fix/cli-lifecycle-graceful-exit`

## Problem

The datashare pipeline uses a "POISON pill" sentinel (`PipelineTask.PATH_POISON` =
`Paths.get("POISON")`, `PipelineTask.STRING_POISON` = `"POISON"`) as an end-of-stream
signal on the shared Redis queues:

- `ScanTask` appends one `PATH_POISON` when the scan finishes.
- `IndexTask` drains via `drainer.drain(PATH_POISON)` and stops when it sees it.
- `DeduplicateTask` consumes with `inputQueue.take()` until `PATH_POISON`, then forwards
  one `PATH_POISON` to its output queue.
- `ExtractNlpTask` and `CategorizeTask` poll with a timeout but also stop on
  `STRING_POISON`; `CategorizeTask` offers one `STRING_POISON` to its output queue.

This model breaks the target deployment topology: **SCAN runs on one instance while
INDEX runs on many instances in parallel, all consuming one shared Redis queue, each as
its own CLI run.** A single POISON cannot cleanly terminate N independent INDEX
consumers, which is why operators currently push one POISON per INDEX instance by hand.
Legacy POISON entries also remain in production Redis queues today.

The single-box path only works because SCAN fully populates the queue (ending in POISON)
*before* INDEX drains, so the queue is never transiently empty. `queuePoll` is never set
anywhere in datashare, so the drainer runs with its default `0` (non-blocking) timeout.

## Goals

1. Stop producing POISON entries (ScanTask, DeduplicateTask, CategorizeTask).
2. Terminate on a quiet queue via the poll timeout instead of a POISON signal, with a
   default that cannot silently exit early on a transiently-empty shared queue.
3. Silently skip any legacy POISON entries encountered during consumption (log at debug),
   rather than index a file literally named `POISON`, crash, or stop consuming.
4. Leave extract-lib's `drain(poison)` API intact; only change how datashare calls it.
5. Preserve CLI backward compatibility: no removed options, no renamed queues.

## Non-goals

- Task lifecycle / run termination. The branch already scopes run termination to a
  per-run id. This spec is only about queue end-of-stream.
- Any change to extract-lib.

## Design

### Termination model

Replace the POISON end-of-stream signal with **quiet-queue termination via the poll
timeout**, and make that timeout safe by default.

- Reuse the existing `queuePoll` option key (no rename). Add a datashare constant
  `DEFAULT_QUEUE_POLL = "120s"`.
- Draining stages (INDEX, DEDUPLICATE) resolve their poll window from `queuePoll`,
  defaulting to **120s** when the operator has not set one. An explicitly provided value,
  including `0`, is honored as an operator override (this is the plain "positive default"
  behavior, not a hard floor that rejects `0`).
- Semantics: an INDEX instance concludes the shared queue is drained only after
  `queuePoll` (120s) of genuine silence, giving SCAN time to start / recover from a
  transient gap.

Honesty note / known limitation: a fixed timeout makes premature exit *unlikely*, not
*impossible*. If SCAN stalls longer than `queuePoll`, an INDEX box will still conclude
"drained". The 120s default plus a documented override is the accepted mitigation.

### Surfacing the behavior (help text)

extract-lib's `DocumentQueueDrainer` `@Option(name = "queuePoll", ... "Defaults to 0.")`
must not change (goal 4). Surface the datashare pipeline behavior in datashare's own CLI
help, registering `queuePoll` in `DatashareCliOptions` alongside `pollingInterval`, with
a description such as:

> Time INDEX/DEDUPLICATE wait for new documents on the shared queue before concluding it
> is drained (e.g. "120s"). Raise it above your worst-case gap between SCAN producing and
> INDEX consuming when running SCAN and INDEX on separate instances. Defaults to 120s for
> pipeline draining.

The option is registered for visibility/documentation without a joptsimple `defaultsTo`,
so the task layer remains the single source of the default value and can still detect
"operator did not set it".

### Shared helpers (`PipelineTask`)

Keep `PATH_POISON` and `STRING_POISON` (referenced by tests and needed to recognize
legacy entries). Add:

- `public static boolean isPoison(Path path)` -> `PATH_POISON.equals(path)`
- `public static boolean isPoison(String id)` -> `STRING_POISON.equals(id)`
- `protected Duration pipelineQueuePoll()` -> reads `queuePoll` from the
  `PropertiesProvider`, defaulting to `DEFAULT_QUEUE_POLL` (120s). Single source of the
  default so INDEX and DEDUPLICATE stay consistent.

### Per-stage changes

**ScanTask** — remove `outputQueue.add(PATH_POISON)`. Return value (scanned count)
unchanged.

**IndexTask**
- Switch `drainer.drain(PATH_POISON)` to `drainer.drain()` (stop on `poll() == null`).
- Wrap the drain consumer so entries where `isPoison(path)` is true are logged at debug
  and skipped: never handed to the `DocumentConsumer`, never counted. (The drainer's own
  `consumed` counter still increments for a skipped path; this only affects the progress
  denominator and is acceptable.)
- If the operator did not set `queuePoll`
  (`propertiesProvider.get("queuePoll").isEmpty()`), call
  `drainer.setPollTimeout(pipelineQueuePoll())` (120s). Otherwise honor the configured
  value.

**DeduplicateTask**
- `removeDuplicates()` unchanged.
- Replace the `inputQueue.take()`-until-`PATH_POISON` loop in `transferToOutputQueue`
  with a `inputQueue.poll(pipelineQueuePoll().toMillis(), MILLISECONDS)`-until-`null`
  loop.
- Skip any legacy POISON encountered (log at debug), forwarding only real paths.
- Stop emitting `PATH_POISON` to the output queue.

**ExtractNlpTask**
- Stop terminating on `STRING_POISON`. Restructure the loop so a poison `docId` is
  logged at debug and skipped (continue), a `null` poll decrements `nbMaxPolls`, and a
  real `docId` is processed. Termination is solely via the existing
  `NB_MAX_POLLS x pollingInterval` quiet-queue window.

**CategorizeTask**
- Same skip-and-continue treatment of `STRING_POISON` on input.
- Remove the final `outputQueue.offer(STRING_POISON)`.

### extract-lib

Untouched. `DocumentQueueDrainer.drain(Path poison)` and `drain()` both remain;
datashare simply calls `drain()`.

## Testing (TDD, red-green for each behavior)

- **ScanTaskTest**: output queue size no longer includes a `+1` POISON
  (`test_scan` expects 3, `test_scan_with_queue_name` expects 3).
- **DeduplicateTaskTest**:
  - Legacy POISON in the input queue is skipped and **not forwarded** (output contains no
    POISON).
  - Duplicates still removed; output size no longer includes a POISON.
  - Termination on a quiet queue (no POISON at all) transfers all real paths and stops.
  - Tests pass a small `queuePoll` (e.g. `"10ms"`) so they terminate quickly instead of
    waiting 120s.
- **IndexTask (unit + IntTest)**:
  - A `POISON` entry in the input queue is skipped during INDEX and not indexed as a file
    named `POISON`; surrounding real documents are indexed.
  - Termination on a quiet queue with a positive `queuePoll` (>= 1s, drainer honors
    whole-second granularity): available documents drain, then the task stops after the
    poll window.
  - `pipelineQueuePoll()` returns 120s when `queuePoll` is unset and returns the
    configured value when set.
- **ExtractNlpTask / CategorizeTask**:
  - A POISON entry is skipped without terminating the loop.
  - CategorizeTask emits no POISON to its output queue.

### Test-suite commands

Run `datashare-app` and `datashare-tasks` suites. Pre-existing failures to ignore in this
devenv: `ExtensionLoaderTest#test_load_jars`, `TaskManagersIntTest` (needs RabbitMQ), and
`TaskManagerAmqpTest#test_task_result_uri_result_type` (order-dependent flake, fails on
main too).

## Commits

Semantic commits via the `/commit` skill, no push. Grouped so each is coherent and green.
`DeduplicateTask` and `CategorizeTask` change poison production and consumption in the
same methods, so each file is touched in a single commit rather than split:

1. Shared helpers (`isPoison`, `pipelineQueuePoll`) + `queuePoll` default and CLI help.
2. Stop producing POISON on scan (ScanTask), with tests.
3. Quiet-queue termination + skip legacy POISON across consumers (IndexTask,
   DeduplicateTask, ExtractNlpTask, CategorizeTask) — including DeduplicateTask and
   CategorizeTask ceasing to forward POISON downstream, with tests.
