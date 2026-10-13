# DeduplicateTask: dedup per entry instead of once at startup

Issue: [#2296](https://github.com/ICIJ/datashare/issues/2296)
Branch: `fix/2296-incremental-deduplicate`, based on `refactor/remove-poison-pill` (PR #2289)
Date: 2026-07-30

## Problem

`DeduplicateTask.call()` calls `inputQueue.removeDuplicates()` exactly once, then drains the
queue forwarding every entry unconditionally (`transferToOutputQueue(p -> true)`). SCAN runs
concurrently with DEDUPLICATE, so any path SCAN enqueues after that single call reaches the index
queue undeduplicated and gets indexed twice.

The task was written in 2019 (`51a86d617`, issue #316) as a one-shot batch job over a queue at
rest: it returned early on an empty queue and did nothing else. It later grew
`transferToOutputQueue` and became a streaming pipeline stage while keeping the batch premise. The
two are incompatible: a streaming stage has to dedup per entry.

Not a regression from PR #2289. Before it, `CliApp` started `DeduplicateTask` *before* `ScanTask`
and the task blocked on `inputQueue.take()` until `PATH_POISON`, so the stages already overlapped
and `removeDuplicates()` already ran on an effectively empty queue. The stage has therefore never
actually deduplicated anything in a CLI pipeline run.

## Why fix rather than delete

`DEDUPLICATE` is opt-in (`Stage.DEDUPLICATE(false)`, defaults are `SCAN,INDEX,NLP`), reachable
only through `--stages`, referenced in no documentation and by no web endpoint. Deletion was
considered and rejected: `--reportName` has no default ("No report records are saved if not
provided", `DatashareCliOptions.java:728`), so a plain CLI run has no report map and thus no
duplicate protection at INDEX. `DEDUPLICATE` is the only dedup available to those runs. Duplicates
arise from re-scanning a data dir, since the Redis queue survives across runs, and from
overlapping or symlinked scan roots.

## Design

Dedup moves from one upfront scan into the transfer predicate, so every entry is checked as it
flows through, whether it was already queued or arrives mid-drain.

```java
@Override
public Long call() throws Exception {
    super.call();
    // ponytail: on-heap seen-set, roughly 190 bytes per path, so ~2GB at 10M paths. Back it with
    // DocumentCollectionFactory.createMap if a corpus that size ever needs this stage.
    Set<Path> seen = new HashSet<>();
    long[] dropped = {0};
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

`inputQueue.removeDuplicates()` is deleted. The return value keeps its current contract, the
number of duplicates dropped, and now counts them for real.

`long[]` rather than `AtomicLong`: the drain is single-threaded, and an atomic would imply
concurrency that does not exist.

### Memory ceiling

The seen-set holds every distinct path for the duration of the run: roughly 190 bytes per entry
(`UnixPath` byte array, object headers, `HashSet` node, table slot), so about 2GB at 10M paths.
Accepted rather than engineered around, because a corpus that size would use `--reportName`, whose
Redis-backed report map dedups at INDEX with flat heap. The `ponytail:` comment names the ceiling
and `DocumentCollectionFactory.createMap` as the upgrade path.

### Cleanup in the same file

`transferToOutputQueue` returns `originalSize - outputQueue.size()`, which is meaningless once the
producer runs concurrently: `originalSize` is sampled before the drain starts, and the output
queue may already hold entries from an earlier run. No production caller uses it, and neither do
the two existing tests that call the method directly. It becomes `void`, which deletes the
`originalSize` sampling.

The no-argument `transferToOutputQueue()` overload also goes: once `call()` passes a predicate, its
only remaining caller is one test, which can inline `p -> true` itself.

### Explicitly out of scope

No legacy `PATH_POISON` skip is added to `DeduplicateTask`, despite the review suggestion on
#2289. Its queue is a `DocumentQueue<Path>`, so `isLegacySentinel(String)` does not apply, and the
stale entry is already skipped downstream at `IndexTask.java:84`, making it a cosmetic
inconsistency rather than a bug. Adding it would mean moving `PATH_POISON` out of `IndexTask` and
adding a `Path` overload to share a guard that is scheduled for deletion in 21.18, between two
call sites, one of which nobody runs.

## Error handling

Unchanged. `transferToOutputQueue` already handles a poll that throws a wrapped interrupt, the
upstream-producer gate through `drained(taskRepository)`, and the interrupt-flag clearing on
cancellation. The predicate introduces no new failure mode: it neither throws nor performs IO.

## Testing

The two existing pass-through tests and `test_filter_empty` hold unchanged.
`test_filter_queue_removes_duplicates` also holds: two identical paths still yield a return of 1
and an output queue of 1, now via the predicate rather than `removeDuplicates()`.

One test is added, covering the case that fails today: a duplicate enqueued *after* the drain has
started is dropped. It gates the task on a running upstream task and uses the empty-poll
`CountDownLatch` pattern from `UpstreamGateTest` to know the drain has already polled an empty
queue before the duplicate is enqueued, then marks the upstream terminal so the drain ends.
Expected: output queue holds one entry, `call()` returns 1. On the current code the duplicate is
forwarded and the return is 0.

## Success criteria

- A duplicate enqueued while SCAN is still running does not reach the index queue.
- `DeduplicateTaskTest` passes, new test fails on unfixed source.
- No behaviour change for pipelines that do not configure the DEDUPLICATE stage.
