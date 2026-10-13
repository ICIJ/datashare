# Design: gate the INDEX-stage embedded payload on a consumer (#2302)

Part of epic(artifacts) #2229. Follows #2225 (PR #2262), which wired `--artifacts` into the
INDEX stage.

## Goal

Stop `--stages INDEX --artifacts structure` from spooling every embedded document's bytes to
disk when nothing will ever record or read them.

## Current state (already on main)

`IndexTask.call()` opts into artifact generation like this (`IndexTask.java:124-138`):

```java
ArtifactStages.artifactProjectRoot(propertiesProvider).ifPresent(projectRoot -> {
    List<Artifact> selected = ArtifactRegistry.withDefaults(propertiesProvider).select(...);
    List<String> notAtIndexTime = selected.stream().map(Artifact::type)
            .filter(type -> type != ArtifactType.RAW).map(ArtifactType::token).toList();
    if (!notAtIndexTime.isEmpty() && !new PipelineHelper(propertiesProvider).stages.contains(Stage.ARTIFACT)) {
        logger.warn("--artifacts selects {}, which the INDEX stage does not produce, and ARTIFACT is not in "
                + "--stages: only the 'raw' entry will be recorded.", notAtIndexTime);
    }
    extractor.setEmbedOutputPath(projectRoot);
    spewer.setManifestRecorder(new ManifestRecorder(..., selected, ...));
});
```

Commit `c16d34d5a` added that warning and the matching CLI help on both surfaces, which
answers the "silent" half of the issue. The disk half is untouched:

- `setEmbedOutputPath` is called whenever `--artifacts` is present at all, independent of the
  selection. `Extractor.embedOutput` is what makes `EmbedSpawner` write each embedded
  document's bytes during the parse (extract-lib `Extractor.java:398`, `Extractor.java:982`);
  its default is `null`, meaning "write nothing".
- `ManifestRecorder.record` returns early when `raw` is not among the selected types
  (`ManifestRecorder.java:36`), because `raw` is the only type whose payload falls out of the
  streaming parse.

So `--stages INDEX --artifacts structure` writes the full embedded raw payload and records zero
manifest entries. Those bytes are described by no manifest, reclaimed by no later run, and
re-extracted from scratch by `RawArtifact.produce` if `raw` is ever selected.

## The rule

The embed output path exists to materialize `raw` payload. Turn it on only when something will
consume that payload:

- **`raw` is in the selection** — `ManifestRecorder` records an entry pointing at it now; or
- **`ARTIFACT` is in `--stages`** — a later producer reads those bytes instead of re-extracting.

Otherwise leave `embedOutput` at its `null` default.

The rejected alternative was to gate strictly on `raw`. It is the more literal reading of the
flag, but `--stages INDEX,ARTIFACT --artifacts structure` would then lose the warm cache and the
ARTIFACT stage would re-extract every embedded source on demand through
`SourceExtractor.getSource` (`SourceExtractor.java:63`), which is expensive on large containers
such as OSTs. Keeping the cache for combined runs is worth the payload sitting there
un-manifested for the length of the run, because the run that follows consumes it.

## The change (`IndexTask.call()`)

The skipped case is exactly the case the warning already fires on, so the two share one
`PipelineHelper` instead of the current inline construction:

```java
List<Artifact> selected = ArtifactRegistry.withDefaults(propertiesProvider).select(propertiesProvider.get(ARTIFACTS_OPT).orElse(null));
boolean rawSelected = selected.stream().anyMatch(artifact -> artifact.type() == ArtifactType.RAW);
boolean artifactStageRuns = new PipelineHelper(propertiesProvider).stages.contains(Stage.ARTIFACT);
List<String> notAtIndexTime = selected.stream().map(Artifact::type)
        .filter(type -> type != ArtifactType.RAW).map(ArtifactType::token).toList();
if (!notAtIndexTime.isEmpty() && !artifactStageRuns) {
    logger.warn("--artifacts selects {}, which the INDEX stage does not produce, and ARTIFACT is not in --stages: {}",
            notAtIndexTime, rawSelected ? "only the 'raw' entry will be recorded."
                    : "this stage records nothing and writes no embedded payload.");
}
// extract-lib spools every embedded document's bytes here during the parse. Nothing reads them
// unless raw is recorded now or the ARTIFACT stage consumes them later, so an --artifacts
// selection that wants neither must not pay for them.
if (rawSelected || artifactStageRuns) {
    extractor.setEmbedOutputPath(projectRoot);
}
spewer.setManifestRecorder(new ManifestRecorder(new FilesystemManifestRepository(), projectRoot, selected, ArtifactStages.force(propertiesProvider)));
```

Two deliberate details:

- **The warning tail becomes conditional.** With `--artifacts structure` no `raw` entry is
  recorded either, so the current "only the 'raw' entry will be recorded" would be false once
  the payload write is gated.
- **`setManifestRecorder` stays unconditional.** `ManifestRecorder.record` already no-ops when
  `raw` is unselected; skipping the call would buy a branch and nothing else.

No new abstraction. Lifting the predicate into `ArtifactStages` for an Elasticsearch-free unit
test was considered and rejected: a helper with one caller would test the predicate and not the
wiring, which is where the bug lives.

## Behavior

| `--artifacts` | `ARTIFACT` in `--stages` | Before | After |
|---|---|---|---|
| `raw`, or bare `--artifacts` | either | payload written, `raw` entries recorded | unchanged |
| `structure` / `page` | yes | payload written, entries recorded at ARTIFACT | unchanged |
| `structure` / `page` | no | payload written, nothing recorded | no payload, warning names it |

Only the bottom-right cell moves. `ArtifactPath.projectRoot` performs no `mkdir`, and the
early-returning `ManifestRecorder` writes no manifest, so that run now touches the artifact
directory zero times.

`stages` reaches the task args in CLI mode via `propertiesToMap(properties)`
(`CliApp.java:222`). When it is absent, `PipelineHelper` defaults to `SCAN,INDEX,NLP`, which
excludes `ARTIFACT`; that is the truthful answer for web mode, where the ARTIFACT stage is never
part of the pipeline, and the gate correctly falls back to `rawSelected` there.

## Testing

TDD, RED before any production code. `IndexTaskIntTest` already has `ClosingProbeIndexTask`
capturing each created `Extractor` in `CREATED_EXTRACTORS` (`IndexTaskIntTest.java:51`), and
`Extractor.getEmbedOutputPath()` is public, so the assertions need no Mockito. Each test uses a
temporary `artifactDir` and queues `docs/embedded_doc.eml`, so there is an embedded document to
spool.

1. `artifacts=structure`, `stages=SCAN,INDEX` — after `call()`, `getEmbedOutputPath()` is
   `null` and the project root under `artifactDir` was never created. Fails today: the path is
   set and the bytes land on disk. The `logback` assertion on the corrected warning tail rides
   along here.
2. `artifacts=structure`, `stages=SCAN,INDEX,ARTIFACT` — the path is set. Guards against
   over-tightening the gate.
3. `artifacts=raw`, `stages=SCAN,INDEX` — the path is set. Guards the common path.

## Out of scope

- Rejecting the selection outright at INDEX. `--artifacts` is a single flag shared by both
  stages, so `--stages INDEX --artifacts page` is the same string a later `--stages ARTIFACT`
  invocation uses; a hard error would force different flags per invocation. The warning stays
  the answer to "silent".
- Reclaiming payload already written to disk by earlier runs.
- Per-stage artifact selection.
