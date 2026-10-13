# ARTIFACT & embedded-extraction reliability: findings report

Date: 2026-07-15
Branch: `audit/artifact-embedded-reliability` (datashare) + extract-lib `master`
Method: verification harness (synthetic corpus + coverage checker + chaos runs
against real Elasticsearch), a read-only code audit across datashare and
extract-lib, and one bounded real-data run on a 66 MB Outlook OST.

This report is the Phase 2 deliverable. It ranks every failure mode found,
gives the evidence, and proposes a fix per finding. The "fix now" list at the
end needs your sign-off before Phase 3 starts.

## TL;DR

The ARTIFACT task cannot currently be relied on to make embedded documents
retrievable. The harness and audit found **two critical, independently
sufficient causes of "missing files"** plus four more high-severity gaps:

1. **F3 (real data, total):** on OST/PST sources, ARTIFACT re-extraction uses a
   different, weaker parser than INDEX, so it fails for **100%** of documents on
   the real 66 MB OST tested ("OST 2013 support not added yet"). Every embedded
   doc indexed from that OST is unretrievable via the artifact cache.
2. **F1 (concurrency):** with `parallelism > 1`, workers re-parse the whole root
   per child and race writing sibling files, silently losing a nondeterministic
   subset of documents while the task reports success.
3. **N4 (crash):** the root file stream is never closed, leaking one descriptor
   per extraction; on a corpus with many embeds this reaches "Too many open
   files" — the instability symptom you reported.

The good news: the resume path is sound (kill mid-run + rerun converges to full
coverage), and the T10 accounting fix now makes failures visible in the run
summary instead of silent.

## Severity-ranked findings

| ID | Severity | Where | One line |
|----|----------|-------|----------|
| F3 | **Critical** | datashare `SourceExtractor` / extract-lib parser wiring | ARTIFACT uses vanilla `AutoDetectParser`; INDEX uses `ResilientOutlookPSTParser`. OST/PST embeds 100% unretrievable. |
| F1 | **Critical** | extract-lib `EmbeddedArtifactWriter` + `EmbeddedDocumentExtractor` | parallelism>1 race + full-root re-parse per child → silent loss on containers with embeds. |
| N4 | **High** | extract-lib `EmbeddedDocumentExtractor:89,105` | root `FileInputStream` never closed → FD leak → crash under many embeds. |
| N2 | **High** | extract-lib `EmbeddedDocumentExtractor:92-98` + `EmbeddedArtifactWriter:32-63` | cache read trusts file existence, ignores manifest/digest; non-atomic writes → serves truncated garbage / permanent 404, no live fallback. |
| N1 | **High** | datashare `ArtifactTask`/`EnqueueFromIndexTask`/`CliApp` | no poison pill, no ENQUEUEIDX→ARTIFACT barrier → workers exit early → silent holes (CLI path confirmed). |
| N3 | **Med-High** | datashare `SourceExtractor:122-124,151-153` | SERVER mode drops legacy digester → 404; ARTIFACT caching path has no digester fallback → config drift writes dead-keyed artifacts. |
| F2 | **Medium** | extract-lib parse path | corrupt embedded member served as garbage bytes with no failure signal. |
| A3 | **Medium** | datashare `ArtifactTask:146` + extract-lib `EmbedParser` | no per-doc timeout and no cooperative interrupt on the retrieval path → a pathological doc hangs a worker. |
| A9 | **Medium** | datashare `SourceExtractor:108-114` / `DocumentResource:523` | failed digester attempts at DEBUG; opaque 404 → hard to diagnose. |
| A2 | **Low** | datashare `ArtifactProducer:55` | F1's caught exception is attributed to the wrong doc id in the log; net failure count still non-zero. |

Verified **OK** (no action needed for retrieval): orphaned embeds (retrieval
re-derives from the on-disk source file, not the indexed root); `maxEmbedDepth`
(retrieval has no depth cap, so retrieval depth ≥ index depth — safe direction);
kill+rerun convergence; T10 accounting.

## Evidence per finding

### F3 — Parser mismatch between INDEX and ARTIFACT (Critical, real-data confirmed)

Real-data run (66 MB OST from corpus A, branch-built jar): INDEX indexed 289 documents
in ~155 s; ARTIFACT attempted all 289 and **produced 0**, failing every one with
`TikaException: OST 2013 support not added yet` in ~87 s (no hang). The coverage
checker reported 289/289 holes, matching the task's own `nbFailed=289`.

Root cause, confirmed in code:
- INDEX time, `extract-lib Extractor.java:259`:
  `replaceParser(OutlookPSTParser.class, parser -> new ResilientOutlookPSTParser());`
- ARTIFACT/download time, `extract-lib EmbeddedDocumentExtractor.java:55`:
  `this.parser = new DigestingParser(ocr ? new AutoDetectParser() : createParserWithoutOCR(), digester, false);`

The retrieval path never installs the `ResilientOutlookPSTParser` replacement, so
Tika's vanilla `OutlookPSTParser` runs and rejects OST 2013. Because F3 fails
before any embed is written, F1 could not even reproduce on this corpus — F3
masks it. This is the most likely real-world driver of "missing files" on the
mail-heavy projects.

Impact: on any OST/PST-sourced project, embedded documents are indexed but the
artifact cache is never populated and live download of embeds also fails with the
same parser. Loud (T10 counts it), not silent.

Fix: the retrieval/ARTIFACT extractor must apply the same parser replacements
INDEX uses (at minimum `ResilientOutlookPSTParser` for `OutlookPSTParser`).
Cleanest in extract-lib: have `EmbeddedDocumentExtractor` build its parser from
the same configuration path `Extractor` uses (the `replaceParser` set), rather
than a bare `AutoDetectParser`. datashare side confirms via a coverage run.

### F1 — Concurrency race + full-root re-parse (Critical, harness-confirmed)

`ArtifactCoverageIntTest` (parallelism 2) and `ArtifactChaosIntTest`
(parallelism 4) both go RED reproducibly on the synthetic corpus: `checked`
stable at 214, holes nondeterministic (2–5), always on the 200-wide `wide.zip`
root, occasionally on a small 2-child container. Reason recorded: `NoSuchFileException`
in `EmbeddedArtifactWriter.write`.

Mechanism (audit item 4, `EmbeddedDocumentExtractor.java:240-250`,
`EmbeddedArtifactWriter.java:32-63`): for each polled child, `extractEmbeddedSources`
runs `extractAll`, which re-parses the **whole root** and writes **every** child's
raw bytes. Two workers on two children of the same root both run `extractAll` and
both `Files.createDirectories` + `Files.copy(REPLACE_EXISTING)` the same ~200
paths with no lock → race → `NoSuchFileException`, caught in `ArtifactProducer.produce`,
logged ERROR, doc left with no manifest entry. Also O(N × root-parse) wasted work.

Fix: atomic writes (temp + `ATOMIC_MOVE`) under a per-root lock in
`EmbeddedArtifactWriter`, and de-duplicate the work (one `extractAll` already
writes all siblings — either record COMPLETE for all children in that pass, or
skip re-parse when the child's `raw` already exists). One change closes the race,
the redundancy, and N2's truncation window.

### N4 — Root FileInputStream never closed (High, crash)

`EmbeddedDocumentExtractor.java:89` (`extractAll`) and `:105` (`extract`) pass
`new FileInputStream(root)` straight to `parser.parse(...)`. Tika does not close a
caller-supplied stream and neither method wraps it, so **every** call (success
path included) leaks one root FD, amplified N-fold by F1's redundant re-parses.
Over a long run this hits `Too many open files`, which then fails unrelated docs →
more holes. This is the most likely explanation for the crash/hang instability on
embed-heavy projects.

Fix: `try (InputStream in = Files.newInputStream(root)) { parser.parse(in, ...); }`
in both methods.

### N2 — Cache read ignores integrity, manifest, and has no fallback (High)

`EmbeddedDocumentExtractor.extract` returns the cached `raw` on `File.exists()`
alone (`:92-98`), never checking the manifest COMPLETE status the writer maintains
nor verifying bytes, and raw is written non-atomically. Three defects: (1) a
truncated raw is served as garbage forever (intersects F2), self-heals only via
`--artifactsForce`; (2) a missing/corrupt `raw.json` throws out of `extract` with
no live-parse fallback → permanent 404 despite the source being re-derivable;
(3) the read path bypasses the entire COMPLETE-status crash-safety protocol.

Fix: atomic raw/raw.json writes; treat a load failure or absent sidecar as a cache
miss and fall through to live parse; gate the hit on manifest COMPLETE or a digest
check.

### N1 — No end-of-stream signal for the ARTIFACT queue (High, silent-loss)

`ArtifactTask.runWorker` exits on a single empty poll of `pollingInterval` (default
60 s, `ArtifactTask.java:137`). `EnqueueFromIndexTask` never offers a poison pill
(contrast `ExtractNlpTask`/`CategorizeTask`), and `CliApp` starts ENQUEUEIDX and
ARTIFACT concurrently with no barrier (`CliApp.java:194/210/212`;
`TaskManager.startTask` is non-blocking). Two windows lose data silently: startup
(first scroll+enqueue > 60 s → all workers exit before any entry) and mid-run (a
slow/loaded ES scroll batch leaves the queue empty > 60 s → workers exit while
more is coming). Not counted by any counter. CLI/local path confirmed; Temporal
chaining ordering needs a runtime check.

Fix: give ENQUEUEIDX an explicit end-of-stream signal (N poison pills after the
scroll, ArtifactTask exits on the pill) or a real barrier ordering the stages. The
60 s timeout must not be the correctness mechanism.

### N3 — Digester drift defeats the cache; SERVER mode 404 (Med-High)

On-demand download tries three digesters (`SourceExtractor.java:79-87`) covering
`noDigestProject`/`defaultProject` drift — but only outside SERVER mode:
`mightUseLegacyDigester` is false whenever `isServerMode()` (`:151-153`), so a
legacy-keyed embed is a permanent 404 on server deployments (Gap A). The ARTIFACT
caching path picks a single digester from current config (`:122-124`) with no
fallback, so if config drifts from the INDEX run the cache is written under keys
the indexed id never matches — every download then misses and pays a full live
parse, and disk fills with dead-keyed orphans (Gap B).

Fix: don't unconditionally disable the legacy digester on read; reuse the ordered
digester list (or key the cache off the indexed `document.getId()`) on the caching
path.

### F2 — Corrupt embed served silently as garbage (Medium)

`ArtifactChaosIntTest.corrupt_member_does_not_break_sibling_coverage` confirmed:
Tika swallows a corrupt OOXML member as a WARN inside `extractAll`, so no
production failure is counted and the member is served as possibly-garbage bytes
with a terminal manifest and no datashare-level signal. Folds into N2's integrity
fix (a digest check on the cached bytes would surface it).

### A3 — No per-doc timeout / no cooperative interrupt (Medium, hang)

No time bound around `producer.run` (`ArtifactTask.java:146`), and the retrieval
extractor descends via `EmbedParser`, which has no interrupt check (unlike
`EmbedSpawner.parseEmbedded`). A pathological root hangs a worker; `shutdownNow`'s
interrupt is ignored by CPU-bound parsers. Fix: per-doc `Future` timeout, or push
the `EmbedSpawner` interrupt check into the shared `EmbedParser` path.

### A9 — Failure UX (Medium)

Three failed digester attempts at DEBUG (`SourceExtractor.java:108-114`), final
give-up throws `ContentNotFoundException` → bare 404 (`DocumentResource.java:523`).
Fix: log the final give-up at WARN/ERROR with doc id, root id, project, schemes
tried; consider distinguishing "not found" from "cache corrupt" in the response.

## Proposed fix plan

Fixes cluster by repo. Several criticals share a single extract-lib change.

**extract-lib (`../extract`), own branches:**
- E1 (F3): retrieval extractor applies INDEX's parser replacements (ResilientOutlookPSTParser). **Critical.**
- E2 (N4): close the root stream in `extract`/`extractAll`. **High, one-liner.**
- E3 (F1 + N2 + F2): atomic raw/raw.json writes + per-root lock in `EmbeddedArtifactWriter`; de-duplicate the per-child re-parse; cache read verifies integrity + live fallback. **Critical/High.**
- E4 (A3): cooperative interrupt in `EmbedParser`. **Medium.**

**datashare (this branch or `fix/*` off main):**
- D1 (N1): poison-pill / barrier for the ARTIFACT queue. **High.**
- D2 (N3): digester fallback on the caching path; don't drop legacy digester on SERVER read. **Med-High.**
- D3 (A9): failure logging + 404 clarity. **Medium.**
- D4 (F3 verify): re-run the coverage checker on the real OST after E1 to confirm coverage. **Critical verification.**
- T10 (accounting): **done** on this branch.

**Fix-now (silent-loss / crash / hang):** F3 (E1), F1+N2+F2 (E3), N4 (E2),
N1 (D1), N3 (D2), A3 (E4). **Fix-plan-only for now:** A9 (D3), A2 (log
attribution).

## Acceptance criteria for the fixes

- Coverage checker passes on the synthetic corpus at parallelism 4 across
  repeated runs (F1 is racy — a single green run is not proof; run N times or
  raise parallelism).
- A real-OST coverage run goes from 0% to full coverage after E1 (D4).
- A long embed-heavy run does not leak FDs (N4).
- CLI ENQUEUEIDX→ARTIFACT cannot report success with unconsumed queue entries (N1).

## Deferred: large-corpus supervised soak

The full corpora are large (corpus A 48 GB incl. a single 50.8 GB OST; corpus B
34 GB). A full soak is a multi-hour, disk-risk job and is deferred to a
supervised run (with the branch-built jar, `--stages` explicit, and
disk headroom). The 66 MB OST run already established F3 on real data; the soak's
remaining value is scale/timing/hang confirmation, best done after E1–E4 land.
Confidential-data rule was honored: nothing copied into the repo, aggregates only.
