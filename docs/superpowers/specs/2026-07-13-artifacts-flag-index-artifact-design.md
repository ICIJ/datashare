# Design: `--artifacts` flag in INDEX + ARTIFACT stages (#2225)

Part of epic(artifacts) #2229. Follows the merged foundation (#2223 type abstraction +
registry, #2224 manifest.json, PR #2231) and #2256 (ARTIFACT parallelism).

## Goal

Complete the opt-in, type-selectable artifact generation story on **both CLI surfaces**
(JOpt and picocli) and **both stages** (INDEX and ARTIFACT), for the one producer that
exists today (`raw`).

## Current state (already on main)

- `ARTIFACTS_OPT` (`artifacts`) and `ARTIFACTS_FORCE_OPT` (`artifactsForce`) exist in
  `DatashareCliOptions` (JOpt surface) with `artifacts()` / `artifactsForce()` parsers.
- ARTIFACT stage fully wired: `ArtifactTask` reads `--artifacts` -> `ArtifactRegistry.select()`
  (bare / `true` = all, CSV = subset, unknown = error) -> default all. `--artifactsForce`
  honored via `ArtifactProducer`.
- During INDEX, extract-lib's `EmbedSpawner` writes embedded `raw` bytes only when the
  extractor's `embedOutput` option is set. **Correction found during implementation:** the
  stage-run INDEX path never sets `embedOutput` (it is set only in `DocumentResource`), so
  `--artifactDir` alone does NOT cause embedded bytes to be written during INDEX. The manifest
  recorder therefore requires INDEX to also wire `embedOutput = artifactDir/<project>` (Task 7),
  in the same content-addressed layout (`ArtifactUtils.getEmbeddedPath` == `ArtifactPath.dir`)
  the ARTIFACT stage uses. **No manifest is written during INDEX today.**

## Scope

**In scope**

1. picocli `--artifacts` / `--artifactsForce` in `PipelineOptions`.
2. INDEX inline production via a **manifest-only recorder** (default off).
3. Fail-fast guard (`--artifacts` requires `--artifactDir`) in both tasks.
4. Tests: picocli parsing, per-stage defaults, guard, INDEX == ARTIFACT parity.

**Out of scope**

- Structure/page inline during INDEX: extract-lib produces no such bytes during the parse,
  so only `raw` is manifest-only-recordable inline. Deferred to when those producers land
  (#2226 / #2227).
- Removing "the PoC's unconditional structure write": nothing on main writes structure
  today, so this checklist item from the issue is moot here.
- `ArtifactResource` / serving (#2228).

## Component 1: picocli options (`PipelineOptions`)

Mirror the JOpt `withOptionalArg` semantics:

```java
@Option(names = {"--artifacts"}, arity = "0..1", fallbackValue = "true",
        description = "Artifact types to produce (comma-separated, e.g. " + ArtifactType.tokens()
                + "). Bare flag = all types.")
String artifacts;   // null when absent

@Option(names = {"--artifactsForce"}, arity = "0..1", fallbackValue = "true", defaultValue = "false",
        description = "Reprocess artifacts even when an up-to-date manifest entry exists.")
boolean artifactsForce;
```

`toProperties()`:
- `putIfNotNull(ARTIFACTS_OPT, artifacts)` — absent -> key omitted -> no inline production.
- `put(ARTIFACTS_FORCE_OPT, artifactsForce)`.

`arity = "0..1"` + `fallbackValue = "true"` reproduces bare-flag = all vs `=raw,structure` =
subset, matching the JOpt side so both surfaces resolve identically through
`ArtifactRegistry.select()`.

## Component 2: INDEX inline manifest recorder

A focused collaborator (working name `ManifestRecorder`) that records a manifest entry for an
already-extracted document, **without re-extraction**.

- Built only when `ARTIFACTS_OPT` is present and the selection includes `raw`; otherwise
  null / no-op.
- Wired into `ElasticsearchSpewer.writeDocument`, right after `indexer.add(document)`:
  compute `docArtifactDir = ArtifactPath.dir(projectRoot, doc.getId())` and record the raw
  entry via `ManifestRepository`. Duplicates (the existing `isDuplicate` branch) are skipped,
  same as the ARTIFACT stage.
- Skip-if-current and `force` honored, reusing `ArtifactProducer`'s logic path where practical.
- **Parity guarantee:** the raw `ManifestEntry` construction (root, i.e. extractionLevel <= 0,
  -> `ManifestEntry.empty`; embedded -> `ManifestEntry.singleFile(contentType, name)`) is
  extracted into a shared helper on `RawArtifact` and called by both `RawArtifact.produce()`
  and the recorder, so INDEX and ARTIFACT emit byte-identical manifests.

Default off: no `--artifacts` -> recorder absent -> today's behavior unchanged (extract-lib
still caches raw bytes if `--artifactDir` is set; no manifest written).

### Why manifest-only (not full `ArtifactProducer` inline)

During INDEX the extraction is already running and `EmbedSpawner` writes the raw bytes.
Running `ArtifactProducer.run()` per doc would call `extractEmbeddedSources`, re-doing
extraction work already performed. Recording only the manifest entry reuses the bytes on
disk and keeps INDEX cheap while still yielding a result identical to the ARTIFACT stage.

## Component 3: guard (fail fast, both stages)

In `IndexTask` and `ArtifactTask` construction: if `ARTIFACTS_OPT` present and
`ARTIFACT_DIR_OPT` absent -> `IllegalArgumentException("--artifacts requires --artifactDir")`.
`ArtifactTask` already throws on missing `artifactDir`; this makes the condition/message
explicit and adds the symmetric check to INDEX. Each task performs its own check (no central
CLI-layer validation), matching the existing `ArtifactTask` pattern.

## Component 4: tests

- **picocli** (`PipelineOptions`): bare `--artifacts` -> `ARTIFACTS_OPT = "true"`;
  `--artifacts=raw` -> `"raw"`; absent -> key omitted; `--artifactsForce` bare/true/false.
- **Guard:** both tasks throw when `--artifacts` set without `--artifactDir`.
- **Defaults:** INDEX without `--artifacts` writes no `manifest.json`; ARTIFACT without
  `--artifacts` produces all types (raw).
- **Parity:** on a fixture (root + embedded), INDEX-with-`--artifacts` yields the same
  on-disk `raw` bytes + `manifest.json` as INDEX-without + ARTIFACT stage.

## Risks / notes

- The recorder couples `ElasticsearchSpewer` to the artifact subsystem. Kept safe by being
  fully opt-in (null when `--artifacts` absent) and confined to a single post-add call.
- INDEX == ARTIFACT parity for `raw` rests on both using the shared entry builder and the
  same `ArtifactPath` layout (both confirmed in code).
- The spewer is used outside CLI INDEX (e.g. web-triggered indexing); opt-in gating keeps
  those paths unchanged unless `--artifacts` is explicitly supplied.
