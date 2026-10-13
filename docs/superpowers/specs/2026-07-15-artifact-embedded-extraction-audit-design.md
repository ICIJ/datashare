# ARTIFACT task and embedded-document extraction reliability audit

Date: 2026-07-15
Status: approved

## Problem

On projects with many embedded documents, the ARTIFACT task is unstable
(crashes or hangs) and some files end up missing. We need a thorough
analysis of the ARTIFACT task and the embedded-file extraction chain so the
task can be relied on: after a run, Datashare must be able to retrieve every
indexed embedded document, and that completeness must be verifiable.

No specific production run is available for autopsy: the analysis is a code
audit plus a local reproduction, covering both the released code paths and
the new artifacts-foundation code currently on main.

## Scope: the retrieval chain

Embedded-document retrieval depends on three links. A failure in any one
shows up to the user as "missing files", so the audit covers all three.

### Link 1: INDEX-time extraction (extract-lib -> ElasticsearchSpewer)

Every embedded document that gets indexed must be re-derivable later.
Aspects under audit:

- digest stability and parent/root references written at index time
- orphaned embeds when a parse dies mid-tree (root written last)
- benign embed-parse failures and how they affect the embed tree
- extraction-config drift (digest algorithm, project-in-digest,
  maxEmbedDepth) between the INDEX run and later retrieval

### Link 2: ARTIFACT task (cache production)

Flow: `EnqueueFromIndexTask` fills the queue from the index ->
`ArtifactTask` workers poll -> fetch the document from Elasticsearch ->
`SourceExtractor` re-extracts the embedded document from its root file ->
`ArtifactProducer` writes the content-addressed artifact and manifest.

This is where the observed crash/hang symptom lives. Prime suspects:

- `SourceExtractor` re-parses the entire root document for every embedded
  document: hang-prone on pathological files and quadratic-ish on
  containers with thousands of embeds
- no per-document timeout around artifact production
- worker-exit semantics of `inputQueue.poll(pollingInterval)`
- accounting gap: a production failure is logged and isolated
  (`ArtifactProducer.produce` returns false) but `nbSkipped` only counts
  index misses, so the task can finish "successfully" with silent holes

### Link 3: Download path

`SourceExtractor.getSource`, both branches:

- artifact-cache hit: stream the cached file
- cache miss: live re-extraction (the path where the OCR-off and
  embedded-in-tar.gz bugs previously lived)

Trust requires the cache-hit path to be complete and the miss path to be a
correct fallback.

## Audit method

For each link, a code-level audit against a fixed failure-mode checklist.
Each finding is backed by a reproducing test where feasible.

- **Silent loss**: can a document end up with no artifact and no durable
  trace (only a log line)? Can the task report success while incomplete?
- **Crash recovery**: kill the task mid-run. The manifest must never be a
  "ready lie" (COMPLETE stamped only after the payload is written). A rerun
  must converge via skip-if-current, including with `parallelism > 1`.
- **Hangs**: unbounded Tika parses, missing per-document timeout, infinite
  retry via requeue, queue-poll deadlocks.
- **Resource exhaustion**: tmp-file accumulation (zip-bomb class), memory
  under parallelism x giant documents, file-descriptor leaks on long runs.
- **Config drift**: digest algorithm, project-in-digest, or maxEmbedDepth
  differing between the INDEX run and the ARTIFACT run, making the document
  unfindable at re-extraction time.

## Verification harness

The harness turns "I read the code" into "I can rely on it". Three parts,
all committed so any future run can be verified the same way.

### Corpus

A small generated plus curated fixture set exercising the nasty shapes:

- nesting several levels deep (document in zip in msg in zip)
- wide fan-out (one container with hundreds of embeds)
- a corrupt or truncated embed among valid siblings
- an OCR-only embedded image
- an archive container (tar.gz or 7z)
- one large binary

Generated programmatically where possible so it stays small in git.

### Real-life corpora (confidential, local-only)

Actual PST/OST files are available locally for collecting real-life logs
and metrics:

- `<corpus-A>`
- `<corpus-B>`

These files are confidential. They must NEVER be used in committed tests,
never be copied into either repo, and no fixture may be derived from their
content. They serve only for local, manual audit runs (reproducing hangs,
measuring timings, validating the coverage checker against real data), and
findings referencing them must not quote document content.

### Coverage checker

The core deliverable. After INDEX + EnqueueFromIndex + ARTIFACT run against
local Elasticsearch, it mechanically asserts, for every embedded document
in the index:

1. a terminal manifest entry exists in the artifact directory, and
2. `SourceExtractor.getSource` actually returns bytes with the expected
   digest.

Any hole is a failure reporting the document id and reason. The checker is
written so it can also run standalone against a real project's index and
artifact directory, making it a reusable production audit tool.

### Chaos runs

- Kill the ARTIFACT task mid-run, rerun: coverage must converge to 100%
  with no corrupted manifests.
- Run with `parallelism` 4 on the same corpus: same result.
- Feed the corrupt-embed corpus: the task must complete, report the failure
  visibly (not just a log line), and cover everything else.

Shape: JUnit integration tests in `datashare-app` (Elasticsearch at
`elasticsearch:9200` in the devenv), with the coverage checker usable
standalone.

## Deliverables and fix policy

1. **Findings report**: each failure mode found, severity-ranked
   (silent loss > hang > crash > noise), with reproducing evidence and a
   proposed fix. Saved in the repo next to this spec.
2. **Fixes now for criticals**: anything in the silent-loss or hang
   categories is fixed in this cycle, TDD, one branch per concern.
   Lower-severity findings get a fix plan only.
3. **extract-lib findings are in scope for fixing**: criticals found in
   Link 1 are fixed in the extract repo (`~/Repositories/extract` style
   flow used for past OST work) as part of this cycle, on their own
   branches there.
4. **Harness committed** and documented so re-running the reliability
   check is one command.

## Out of scope

- Temporal worker lifecycle management (delegated to Temporal by design).
- New artifact types beyond raw (registry currently wires raw only).
- UI changes; this cycle ends at the `SourceExtractor` download API.

## Success criteria

- The coverage checker passes on the full nasty corpus: every indexed
  embedded document has a terminal manifest entry and downloads with the
  expected digest.
- Chaos runs (mid-run kill + rerun, parallelism 4, corrupt-embed corpus)
  all converge to full coverage with visible, not silent, failure
  reporting.
- A severity-ranked findings report exists, and every silent-loss or hang
  finding has a merged fix or an explicit reproducing test marking it.
