# INDEX embedded-payload gate (#2302) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the INDEX stage from spooling embedded document bytes to disk when neither the `raw` manifest recorder nor a following ARTIFACT stage will consume them.

**Architecture:** `IndexTask.call()` currently calls `extractor.setEmbedOutputPath(projectRoot)` whenever `--artifacts` is present, which is what makes extract-lib's `EmbedSpawner` write each embedded document's bytes during the parse. Wrap that call in a two-clause condition: `raw` is in the selection, or `ARTIFACT` is in `--stages`. The existing once-per-run warning already fires on exactly the skipped case, so both read one shared `PipelineHelper`, and the warning's tail becomes conditional because "only the 'raw' entry will be recorded" is false once the payload write is gated.

**Tech Stack:** Java, Maven multi-module, JUnit 4 with `@Rule`, fest-assert (`org.fest.assertions.Assertions.assertThat`), Mockito, Elasticsearch integration rule.

## Global Constraints

- No new dependencies, no new CLI options, no new classes. The whole production change lives in `datashare-app/src/main/java/org/icij/datashare/tasks/IndexTask.java`.
- `Extractor.embedOutput` defaults to `null` meaning "write nothing", so the gate is implemented by *not calling* `setEmbedOutputPath`, never by passing `null`.
- Log message strings are copied verbatim from this plan. They are asserted by substring in the tests.
- Existing behavior must not change for `--artifacts raw`, for the bare `--artifacts` flag (which selects the whole catalog, `raw` included), or for any run with `ARTIFACT` in `--stages`.
- Tests use `mvn`, not a wrapper: there is no `mvnw` in this repo.

---

### Task 1: Gate the embedded-payload write on a consumer

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/IndexTask.java:124-138`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/IndexTaskIntTest.java`

**Interfaces:**
- Consumes: `ArtifactStages.artifactProjectRoot(PropertiesProvider) -> Optional<Path>`; `ArtifactRegistry.withDefaults(PropertiesProvider).select(String) -> List<Artifact>`; `Artifact.type() -> ArtifactType`; `PipelineHelper(PropertiesProvider).stages -> List<Stage>`; `Extractor.setEmbedOutputPath(Path)` and `Extractor.getEmbedOutputPath() -> Path` (extract-lib, both public); `ArtifactPath.projectRoot(Path artifactDir, String projectName) -> Path`, which resolves to `artifactDir/<projectName>`.
- Produces: nothing new. No signature changes, so no later task depends on this one.

**Background the implementer needs:**

- `IndexTaskIntTest` already contains a `ClosingProbeIndexTask` subclass (line 51) that overrides `createExtractor` and appends every created `Extractor` spy to the static `List<Extractor> CREATED_EXTRACTORS` (line 49). Reuse it. Because the list is static and shared across tests in the JVM, every test that reads it must call `CREATED_EXTRACTORS.clear()` as its first statement, the way `index_task_closes_extractor_after_run` does at line 166.
- The class already has `@Rule public ElasticsearchRule es` and `@Rule public LogbackCapturingRule logback`. `logback.logs(Level.WARN)` returns `List<String>` of formatted messages.
- The class field `map` holds the shared task args (`defaultProject` = `es.getIndexName()`, `queueName` = `test:queue`). Never mutate it; copy with `new HashMap<>(map)` per test.
- `ArtifactStages.artifactProjectRoot` throws `IllegalArgumentException` when `artifacts` is set without `artifactDir`, so every test below must set both.
- The fixture `docs/embedded_doc.eml` carries one attachment (`embedded.pdf`), so a run over it really does produce embedded bytes when the output path is set. `docs/doc.txt` has none, which is why the tests below use the `.eml`.
- The project name resolved by `ArtifactStages.resolveProjectName` for these args is `es.getIndexName()`, because `defaultProject` is set and `projectName` is not.

- [ ] **Step 1: Add the temporary-folder rule and the failing test**

Add this import block to `IndexTaskIntTest.java` alongside the existing imports:

```java
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.junit.rules.TemporaryFolder;
```

Add the rule next to the two existing `@Rule` fields:

```java
@Rule public TemporaryFolder artifactDir = new TemporaryFolder();
```

Add the test:

```java
@Test
public void index_task_writes_no_embedded_payload_when_nothing_will_consume_it() throws Exception {
    CREATED_EXTRACTORS.clear();
    DocumentQueue<Path> inputQueue = inputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getQueueNameFor(Stage.INDEX), Path.class);
    inputQueue.add(Paths.get(ClassLoader.getSystemResource("docs/embedded_doc.eml").getPath()));
    Map<String, Object> args = new HashMap<>(map);
    args.put("artifacts", "structure");
    args.put("artifactDir", artifactDir.getRoot().getPath());
    args.put("stages", "SCAN,INDEX");

    new ClosingProbeIndexTask(spewer, inputQueueFactory, new UpstreamGate.Factory(taskRepository),
            new Task<>(IndexTask.class.getName(), User.local(), args), null).call();

    // structure is produced by the ARTIFACT stage, which this run does not include, and raw is not
    // selected: nobody records or reads the embedded bytes, so extract-lib must not write them.
    assertThat(CREATED_EXTRACTORS.get(0).getEmbedOutputPath()).isNull();
    assertThat(ArtifactPath.projectRoot(artifactDir.getRoot().toPath(), es.getIndexName()).toFile().exists()).isFalse();
    assertThat(logback.logs(Level.WARN).stream()
            .anyMatch(l -> l.contains("records nothing and writes no embedded payload"))).isTrue();
}
```

- [ ] **Step 2: Run the test and confirm it fails**

```bash
mvn -pl datashare-app -am test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='IndexTaskIntTest#index_task_writes_no_embedded_payload_when_nothing_will_consume_it'
```

Expected: FAIL on the first assertion, `getEmbedOutputPath()` returning `<artifactDir>/<index name>` instead of `null`. Paste the failure output before writing any production code. Note: Elasticsearch must be reachable at `elasticsearch:9200` for `ElasticsearchRule`.

- [ ] **Step 3: Add the two regression guards**

These describe behavior that is correct today and must stay correct, so unlike Step 1 they are expected to pass both before and after the change. They exist so Step 5 cannot over-tighten the gate.

```java
@Test
public void index_task_writes_embedded_payload_when_the_artifact_stage_will_consume_it() throws Exception {
    CREATED_EXTRACTORS.clear();
    DocumentQueue<Path> inputQueue = inputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getQueueNameFor(Stage.INDEX), Path.class);
    inputQueue.add(Paths.get(ClassLoader.getSystemResource("docs/embedded_doc.eml").getPath()));
    Map<String, Object> args = new HashMap<>(map);
    args.put("artifacts", "structure");
    args.put("artifactDir", artifactDir.getRoot().getPath());
    args.put("stages", "SCAN,INDEX,ARTIFACT");

    new ClosingProbeIndexTask(spewer, inputQueueFactory, new UpstreamGate.Factory(taskRepository),
            new Task<>(IndexTask.class.getName(), User.local(), args), null).call();

    // the ARTIFACT stage reads these bytes instead of re-extracting every embedded source
    assertThat(CREATED_EXTRACTORS.get(0).getEmbedOutputPath())
            .isEqualTo(ArtifactPath.projectRoot(artifactDir.getRoot().toPath(), es.getIndexName()));
}

@Test
public void index_task_writes_embedded_payload_when_raw_is_selected() throws Exception {
    CREATED_EXTRACTORS.clear();
    DocumentQueue<Path> inputQueue = inputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getQueueNameFor(Stage.INDEX), Path.class);
    inputQueue.add(Paths.get(ClassLoader.getSystemResource("docs/embedded_doc.eml").getPath()));
    Map<String, Object> args = new HashMap<>(map);
    args.put("artifacts", "raw");
    args.put("artifactDir", artifactDir.getRoot().getPath());
    args.put("stages", "SCAN,INDEX");

    new ClosingProbeIndexTask(spewer, inputQueueFactory, new UpstreamGate.Factory(taskRepository),
            new Task<>(IndexTask.class.getName(), User.local(), args), null).call();

    // the ManifestRecorder records a raw entry for the embedded document, which needs the payload
    assertThat(CREATED_EXTRACTORS.get(0).getEmbedOutputPath())
            .isEqualTo(ArtifactPath.projectRoot(artifactDir.getRoot().toPath(), es.getIndexName()));
}
```

- [ ] **Step 4: Run the two guards and confirm they pass on unchanged production code**

```bash
mvn -pl datashare-app -am test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='IndexTaskIntTest#index_task_writes_embedded_payload_when_the_artifact_stage_will_consume_it,IndexTaskIntTest#index_task_writes_embedded_payload_when_raw_is_selected'
```

Expected: PASS, 2 tests. The `-Dtest` selector separates methods with a comma; a `+` silently matches nothing and reports a false green.

- [ ] **Step 5: Implement the gate**

In `IndexTask.call()`, replace the whole body of the `ArtifactStages.artifactProjectRoot(...).ifPresent(projectRoot -> { ... })` lambda (currently `IndexTask.java:125-137`) with:

```java
            List<Artifact> selected = ArtifactRegistry.withDefaults(propertiesProvider).select(propertiesProvider.get(ARTIFACTS_OPT).orElse(null));
            boolean rawSelected = selected.stream().anyMatch(artifact -> artifact.type() == ArtifactType.RAW);
            boolean artifactStageRuns = new PipelineHelper(propertiesProvider).stages.contains(Stage.ARTIFACT);
            // Only raw falls out of the streaming parse (see ManifestRecorder): a selection naming any
            // other type reads as a promise this stage does not keep, so say so rather than drop it in
            // silence. Silent when ARTIFACT is coming, since the run does produce them then: a warning
            // that fires on a correct run is one nobody reads. Once per run, not per document.
            List<String> notAtIndexTime = selected.stream().map(Artifact::type)
                    .filter(type -> type != ArtifactType.RAW).map(ArtifactType::token).toList();
            if (!notAtIndexTime.isEmpty() && !artifactStageRuns) {
                logger.warn("--artifacts selects {}, which the INDEX stage does not produce, and ARTIFACT is not in "
                        + "--stages: {}", notAtIndexTime, rawSelected ? "only the 'raw' entry will be recorded."
                        : "this stage records nothing and writes no embedded payload.");
            }
            // extract-lib spools every embedded document's bytes here during the parse. Nothing reads
            // them unless raw is recorded now or the ARTIFACT stage consumes them later, so a selection
            // that wants neither must not pay the disk for them.
            if (rawSelected || artifactStageRuns) {
                extractor.setEmbedOutputPath(projectRoot);
            }
            spewer.setManifestRecorder(new ManifestRecorder(new FilesystemManifestRepository(), projectRoot, selected, ArtifactStages.force(propertiesProvider)));
```

Three things to leave alone:

- `spewer.setManifestRecorder` stays unconditional. `ManifestRecorder.record` already returns early when `raw` is unselected (`ManifestRecorder.java:36`), so a branch here would buy nothing.
- Do not call `setEmbedOutputPath(null)` in the else case. The field already defaults to `null` and the tests assert that default.
- No imports change: `PipelineHelper`, `Stage`, `ArtifactType`, and `Artifact` are all already imported.

- [ ] **Step 6: Run all three tests and confirm they pass**

```bash
mvn -pl datashare-app -am test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='IndexTaskIntTest#index_task_writes_no_embedded_payload_when_nothing_will_consume_it,IndexTaskIntTest#index_task_writes_embedded_payload_when_the_artifact_stage_will_consume_it,IndexTaskIntTest#index_task_writes_embedded_payload_when_raw_is_selected'
```

Expected: PASS, 3 tests.

- [ ] **Step 7: Run both IndexTask test classes for regressions**

```bash
mvn -pl datashare-app -am test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='IndexTaskTest,IndexTaskIntTest'
```

Expected: PASS. If anything fails, check it against the known environment-related failures before treating it as a regression: four classes in this module fail for environment reasons on any branch, and none of them is an `IndexTask` class, so a failure here is real.

- [ ] **Step 8: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tasks/IndexTask.java \
        datashare-app/src/test/java/org/icij/datashare/tasks/IndexTaskIntTest.java
git commit -m "fix(artifacts): write embedded payload at INDEX only when something reads it"
```

Do not stage anything under `docs/superpowers/`: that path is gitignored (`.gitignore:23`).

---

## Verification against the issue

After Task 1, re-read issue #2302 and confirm the reported command behaves as described:

`--stages INDEX --artifacts structure` now writes no embedded payload, creates no directory under `--artifactDir`, records no manifest, and logs one warning naming `structure` and saying the stage records nothing and writes no embedded payload.

## Out of scope

- Rejecting the selection outright at INDEX. `--artifacts` is one flag shared by both stages, so `--stages INDEX --artifacts page` is the same string a later `--stages ARTIFACT` invocation uses; a hard error would force different flags per invocation.
- Reclaiming payload written to disk by earlier runs.
- Per-stage artifact selection.
