# Language re-detection: lingua guesser + LANGUAGE stage

Two stacked branches:

1. `feat/lingua-language-guesser` (off `main`): replace Optimaize with lingua behind the existing
   `LanguageGuesser` interface. Ships alone: INDEX-time detection improves immediately.
2. `feat/language-stage` (off branch 1): the `LANGUAGE` pipeline stage that re-detects and updates
   the `language` field of already-indexed documents.

## Problem

~500k documents in a project carry `language: ENGLISH` wrongly. Two causes, both structural:

1. `OptimaizeLanguageGuesser.guess` falls back to English whenever Optimaize is not confident:
   `.detect(...).or(LdLocale.fromString("en"))` (datashare-app OptimaizeLanguageGuesser.java:37).
   Every unsure document silently becomes ENGLISH.
2. Language may have been forced at index time with `--language` (ElasticsearchSpewer.java:293 only
   guesses when `document.getLanguage() == null`).

Content is already extracted and sits in the ES `content` field, so re-detection needs no
re-extraction. There is no way today to re-run detection on an existing index. Optimaize itself is
unmaintained (last release 2015, fork archived 2023) and benchmarks well below lingua on short and
noisy text.

## Goals

- Swap the detector to lingua (`com.github.pemistahl:lingua:1.2.2`): real confidence handling,
  `UNKNOWN` instead of a silent English default, markedly better short-text accuracy.
- A `LANGUAGE` pipeline stage runnable like ARTIFACT: `--stages ENQUEUEIDX,LANGUAGE` with a
  `--searchQuery` scope.

## Non-goals

- No NLP re-run of reclassified documents (follow-up: `CREATENLPBATCHESFROMIDX` with a
  `--searchQuery` on the corrected languages).
- No new CLI options, no web API endpoint. Existing options (`--searchQuery`, `--parallelism`,
  `--defaultProject`) cover the need, which keeps the JOpt/picocli dual-surface work at zero.
- No ensemble/staged detection (lingua-only; revisit only if lingua's memory or speed measurably
  hurts a real run).
- No translation cleanup (`content_translated` computed from the wrong source language).
- No use of ES `lang_ident_model_1` (license unverified on prod clusters; keep as a manual
  cross-check tool, not a dependency).

---

## Branch 1: `feat/lingua-language-guesser`

### `LinguaLanguageGuesser implements LanguageGuesser`

Replaces `OptimaizeLanguageGuesser` (datashare-app), same `@Singleton` binding point
(CommonMode.java:489). Delete the Optimaize class and the `com.optimaize.languagedetector`
dependency (datashare-app/pom.xml:101).

- Detector: `LanguageDetectorBuilder.fromAllSpokenLanguages().build()`. Default lazy model loading,
  high accuracy mode. ponytail: all-languages high-accuracy can grow toward ~1GB of lazily loaded
  models on a polyglot corpus; switch to `withLowAccuracyMode()` or a preloaded language subset if a
  real run shows memory pressure.
- Unsure handling: lingua's `detectLanguageOf` already returns its own `UNKNOWN` when no language
  wins; map it to datashare `Language.UNKNOWN` (Language.java:46). No probability threshold code of
  our own. Optionally `withMinimumRelativeDistance(0.1)` to push ambiguous near-ties to UNKNOWN;
  decide from the guesser tests, not upfront.
- Mapping: lingua enum -> ISO 639-1 code -> `Language.parse`. `Language.parse` already returns
  UNKNOWN for anything it cannot map (Language.java:66,80). The datashare enum is the full ISO
  639-1 list (incl. NORWEGIANBOKMAL "nb" / NORWEGIANNYNORSK "nn"), so the mapping should be total;
  a test enumerates lingua's 75 languages and proves it. ES keeps storing the enum constant name
  ("FRENCH"), same as today (pinned by ElasticsearchSpewerTest.java:232).
- Sampling: keep a total 10k-char budget (`MAX_DETECTION_LENGTH`) but build the sample from three
  slices at 0%, 45% and 90% of the content instead of the head only, so documents whose first 10k
  chars are headers/URLs/boilerplate still expose real text. The cap no longer guards the optimaize
  UrlTextFilter O(n^2) regex (that bug leaves with Optimaize); it now only bounds per-doc CPU on
  multi-MB content. Concatenated slices may split words at boundaries; irrelevant for n-gram
  detection.
- Blank/whitespace-only content: return `Language.UNKNOWN` without calling the detector.

### Branch 1 consequences

- INDEX-time detection changes for everyone: documents that used to default to ENGLISH now get a
  real guess or UNKNOWN. Intended, release-notes material. Same for the `NerResource` language
  endpoint (NerResource.java:55).
- The lingua jar embeds all language models: distribution size grows by tens of MB.

### Branch 1 tests (TDD order)

1. French paragraph -> FRENCH (behavior preserved from Optimaize)
2. empty / whitespace / digits-and-punctuation gibberish -> UNKNOWN (RED against current code: today
   this returns ENGLISH — the fallback bug pinned)
3. document whose first 10k chars are URL boilerplate but with French text past the midpoint ->
   FRENCH (pins the three-slice sampling)
4. short one-line text ("Bonjour, comment allez-vous ?") -> FRENCH (pins the short-text gain that
   motivates lingua)
5. lingua-to-datashare Language mapping: enumerate lingua's languages, assert each maps to a
   non-UNKNOWN datashare Language or is on the documented fall-through list

---

## Branch 2: `feat/language-stage` (stacked on branch 1)

### `Stage.LANGUAGE`

Append `LANGUAGE(false)` at the end of the `Stage` enum (datashare-api Stage.java:8), after
ARTIFACT, so existing ordinals do not move. Semantically language detection precedes NLP, but the
stage is a standalone maintenance run; enum position only matters for `PipelineHelper` sorting,
which is correct for `ENQUEUEIDX,LANGUAGE`.

### Enqueue: nothing to build

`EnqueueFromIndexTask` already accepts an arbitrary `--searchQuery`, excludes `content` from the
scroll, and enqueues `DocReference` entries carrying the routing/root id
(EnqueueFromIndexTask.java:78-88). With `--stages ENQUEUEIDX,LANGUAGE` its output queue is
`extract:queue:language` via `PipelineHelper.getNextStage` (PipelineHelper.java:40).

### `LanguageDetectTask`

New `datashare-app/src/main/java/org/icij/datashare/tasks/LanguageDetectTask.java`, modeled on
`ArtifactTask` (same drain-queue + worker-pool shape, ArtifactTask.java:81-139):

- `extends PipelineTask<String>`, `Stage.LANGUAGE`, `@TaskGroup(TaskGroupType.Java)`,
  `@TemporalSingleActivityWorkflow(name = "language", activityOptions = @ActivityOpts(timeout = "P1D"))`
- injected: `Indexer`, `LanguageGuesser`, `DocumentCollectionFactory`
- `PARALLELISM_OPT` worker pool exactly like ArtifactTask (default 1)
- per queue entry:
  1. `DocReference.parse(entry)`, `getDocument(indexer, project, ref, List.of("content_translated"))`
     (PipelineTask.java:89) — content included, translations excluded
  2. missing doc: count as skipped, continue (same contract as ArtifactTask nbSkipped)
  3. `guess = languageGuesser.guess(doc.getContent())`
  4. `guess == doc.getLanguage()`: count as unchanged, no write
  5. else `indexer.update(project, doc.getId(), fields, ref.routing())` where `fields` serializes
     the language the same way `Document` serialization does (assert equality with a spewer-indexed
     doc in tests; do not hand-roll the enum-to-string mapping)
- `UNKNOWN` DOES overwrite a wrong ENGLISH: an honest unknown is queryable, a confident wrong
  English is not
- counters logged at the end: processed / updated / unchanged / skipped / failed; return processed
  count like ArtifactTask

Per-doc single updates, no bulk: ~500k docs at a few ms per get+guess+update is a couple of hours
single-threaded, minutes with `--parallelism`. ponytail: per-doc updates, switch to the ES bulk API
if a real run proves too slow.

### Routing-aware partial update

`Indexer.update(String indexName, String id, Map<String, Object> fields)` (Indexer.java:70) sets no
routing (ElasticsearchIndexer.java:188-193), so it fails with `document_missing_exception` on
embedded documents, which live on their root's shard. Add an overload with a `routing` parameter;
the existing 3-arg method delegates with `null` (builder skips `.routing()` when null).

### Wiring checklist

- `Stage.LANGUAGE` in the enum
- `CliApp` stage-to-task map entry (CliApp.java:192-196)
- `DatashareTaskFactory.createLanguageDetectTask` (DatashareTaskFactory.java:32 area)
- Temporal registration: whatever picks up `@TemporalSingleActivityWorkflow` for ArtifactTask picks
  this one up the same way (verify by grepping the annotation's consumers)

### Usage

```
datashare --mode CLI --stages ENQUEUEIDX,LANGUAGE \
  --defaultProject myproject \
  --searchQuery '{"term":{"language":"ENGLISH"}}' \
  --parallelism 4
```

Omitting `--searchQuery` re-detects the whole project (ENQUEUEIDX default path enqueues everything).

### Branch 2 tests (TDD order)

1. `ElasticsearchIndexer` update-with-routing: index a root + embedded doc via the spewer, partial
   update the child's `language` with routing -> ok; without routing the old overload fails (pins
   why the overload exists)
2. `LanguageDetectTaskTest` (ElasticsearchRule + queue, same rig as ArtifactTask tests):
   - doc indexed `ENGLISH` with French content -> `FRENCH` in ES after the run, other fields intact
   - doc already correct -> no update (assert via unchanged counter)
   - embedded doc (id != root) -> updated, proving routing flows from the queue entry
   - missing doc id in queue -> skipped counter, task still succeeds

---

## Consequences to flag in the PRs

- PR 1: INDEX-time detection changes for everyone (best-guess or UNKNOWN instead of a silent
  ENGLISH default); lingua jar grows the distribution.
- PR 2: entities on reclassified documents were extracted with the wrong-language NLP models;
  re-running NLP on them is a documented follow-up, not part of this change.
- Integration: fast-forward/rebase, PR 2 lands after PR 1 (no squash).
