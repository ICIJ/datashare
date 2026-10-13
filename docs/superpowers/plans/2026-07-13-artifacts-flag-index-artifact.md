# `--artifacts` flag in INDEX + ARTIFACT stages (#2225) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete opt-in, type-selectable artifact generation on both CLI surfaces (JOpt done; add picocli) and both stages (ARTIFACT done; wire INDEX inline), for the one producer that exists today (`raw`).

**Architecture:** During INDEX, extract-lib already writes `raw` bytes to `artifactDir`. A new `ManifestRecorder` writes the `raw` manifest entry per indexed document without re-extraction, invoked from `ElasticsearchSpewer.writeDocument`. Both stages build the entry through a shared `RawArtifact.entryFor(...)` + `ArtifactProducer.stampTerminal(...)`, so INDEX and ARTIFACT emit byte-identical manifests. A fail-fast guard rejects `--artifacts` without `--artifactDir`.

**Tech Stack:** Java, Maven multi-module (datashare-api, datashare-index, datashare-cli, datashare-app), JUnit4, fest-assertions, Mockito, picocli (stage-run CLI), JOpt (legacy CLI).

## Global Constraints

- Commit through the `/commit` skill; semantic-tag messages; no Claude attribution/co-author trailers.
- No em dashes in prose or comments.
- Run a single test class with: `mvn -pl <module> -am test -Dtest=<ClassName> -Dsurefire.failIfNoSpecifiedTests=false` (run from repo root).
- ES-backed tests expect Elasticsearch at `elasticsearch:9200`.
- Module map (do not move classes across modules): `Artifact`, `RawArtifact`, `ArtifactProducer`, `FilesystemManifestRepository`, `ArtifactPath`, `ManifestRecorder` live in **datashare-index**; `ArtifactRegistry` and the stage tasks live in **datashare-app**; `ManifestEntry`, `ManifestEntryStatus`, `ArtifactType`, `ManifestRepository` live in **datashare-api**.
- picocli `@Option(description=...)` must be a compile-time constant String: do NOT call `ArtifactType.tokens()` there (it is a method call and will not compile in an annotation).
- `ArtifactPath.dir(projectRoot, id)` slices `id.substring(0,2)` and `(2,4)`, so test document ids must be at least 4 characters.

---

### Task 1: Shared raw-entry builder

Extract the raw `ManifestEntry` construction into a reusable `RawArtifact.entryFor(Document)` and make `ArtifactProducer.stampTerminal` reusable, so the INDEX recorder (Task 2) emits the exact entry the ARTIFACT stage does.

**Files:**
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/artifact/RawArtifact.java`
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/artifact/ArtifactProducer.java:62-64`
- Test: `datashare-index/src/test/java/org/icij/datashare/text/artifact/RawArtifactTest.java` (create)

**Interfaces:**
- Consumes: `ManifestEntry.empty(Map)`, `ManifestEntry.singleFile(Map, String, String)`, `Document.getExtractionLevel()`, `Document.getContentType()`, `Document.getName()`.
- Produces:
  - `public ManifestEntry RawArtifact.entryFor(Document document)` — root (level <= 0) -> `empty(taskInput())`; embedded -> `singleFile(taskInput(), contentType, name)`. No filesystem access.
  - `static ManifestEntry ArtifactProducer.stampTerminal(ManifestEntry produced)` — package-private static; terminal entry unchanged, otherwise stamped `COMPLETE`.

- [ ] **Step 1: Write the failing test**

Create `datashare-index/src/test/java/org/icij/datashare/text/artifact/RawArtifactTest.java`:

```java
package org.icij.datashare.text.artifact;

import org.icij.datashare.text.Document;
import org.junit.Test;

import java.nio.file.Path;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.DocumentBuilder.createDoc;

public class RawArtifactTest {
    private final RawArtifact raw = new RawArtifact();

    @Test
    public void test_entry_for_root_is_empty() {
        Document root = createDoc("rootrootrootroot").with(Path.of("/tmp/root.pdf"))
                .ofContentType("application/pdf").withExtractionLevel((short) 0).build();

        ManifestEntry entry = raw.entryFor(root);

        assertThat(entry.status()).isEqualTo(ManifestEntryStatus.EMPTY);
        assertThat(entry.filename()).isNull();
    }

    @Test
    public void test_entry_for_embedded_is_single_file() {
        Document embedded = createDoc("embeddedembedded").with(Path.of("/tmp/image2.jpg"))
                .ofContentType("image/jpeg").withExtractionLevel((short) 1).build();

        ManifestEntry entry = raw.entryFor(embedded);

        assertThat(entry.status()).isNull();
        assertThat(entry.contentType()).isEqualTo("image/jpeg");
        assertThat(entry.filename()).isEqualTo("image2.jpg");
        assertThat(entry.taskInput()).isEqualTo(raw.taskInput());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl datashare-index -am test -Dtest=RawArtifactTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL to compile — `cannot find symbol: method entryFor(Document)`.

- [ ] **Step 3: Add `entryFor` and refactor `produce` in `RawArtifact.java`**

Replace the `produce` method body so it delegates to a new public `entryFor`:

```java
    @Override
    public ManifestEntry produce(ArtifactContext context) throws ArtifactException {
        Document document = context.document();
        try {
            // extract-lib writes the raw/raw.json bytes for the embedded subtree as a side effect.
            context.sources().extractEmbeddedSources(context.project(), document);
            return entryFor(document);
        } catch (Exception extractionFailure) {
            throw new ArtifactException("raw extraction failed for " + document.getId(), extractionFailure);
        }
    }

    /** Build the raw manifest entry for an already-extracted document, without touching the
     *  filesystem. Shared by produce() and the INDEX-time ManifestRecorder so both stages emit
     *  the same entry. A root's source is the on-disk original (no payload here), so it records
     *  an empty entry; an embedded node records its single-file payload. */
    public ManifestEntry entryFor(Document document) {
        if (document.getExtractionLevel() <= 0) {
            return ManifestEntry.empty(taskInput());
        }
        return ManifestEntry.singleFile(taskInput(), document.getContentType(), document.getName());
    }
```

- [ ] **Step 4: Make `stampTerminal` reusable in `ArtifactProducer.java`**

Change the method signature at `ArtifactProducer.java:62` from private instance to package-private static (the call site inside `produce` at line 52 stays `stampTerminal(produced)` and still compiles):

```java
    // Producers return EMPTY as-is; any other (status-less) entry is stamped COMPLETE only after
    // its payload has been written, so a crash mid-produce never leaves a "ready" lie.
    static ManifestEntry stampTerminal(ManifestEntry produced) {
        return produced.isTerminal() ? produced : produced.withStatus(ManifestEntryStatus.COMPLETE);
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -pl datashare-index -am test -Dtest=RawArtifactTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (2 tests).

- [ ] **Step 6: Run the existing producer test to confirm no regression**

Run: `mvn -pl datashare-index -am test -Dtest=ArtifactProducerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (existing tests green).

- [ ] **Step 7: Commit** (via `/commit`)

Message: `refactor(artifact): extract shared RawArtifact.entryFor and reusable stampTerminal`

---

### Task 2: `ManifestRecorder`

A collaborator that records the `raw` manifest entry for a document indexed during INDEX, reusing bytes extract-lib already wrote. Honors skip-if-current and force, and no-ops when `raw` is not selected.

**Files:**
- Create: `datashare-index/src/main/java/org/icij/datashare/text/artifact/ManifestRecorder.java`
- Test: `datashare-index/src/test/java/org/icij/datashare/text/artifact/ManifestRecorderTest.java` (create)

**Interfaces:**
- Consumes: `RawArtifact.entryFor(Document)` and `ArtifactProducer.stampTerminal(ManifestEntry)` (Task 1); `ManifestRepository.get/put`; `ArtifactPath.dir(Path, String)`; `ArtifactType.RAW`.
- Produces:
  - `public ManifestRecorder(ManifestRepository repository, Path projectRoot, List<Artifact> selected, boolean force)`
  - `public void record(Document document) throws IOException`

- [ ] **Step 1: Write the failing test**

Create `datashare-index/src/test/java/org/icij/datashare/text/artifact/ManifestRecorderTest.java`:

```java
package org.icij.datashare.text.artifact;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.DocumentBuilder.createDoc;

public class ManifestRecorderTest {
    private static final String EMBEDDED_ID = "eeee1111222233334444555566667777";
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private final ManifestRepository repository = new FilesystemManifestRepository();
    private final RawArtifact raw = new RawArtifact();

    private Path projectRoot() {
        return tmp.getRoot().toPath().resolve("prj");
    }

    private ManifestRecorder recorder(boolean force) {
        return new ManifestRecorder(repository, projectRoot(), List.of(raw), force);
    }

    private Document embedded(String id) {
        return createDoc(id).with(Path.of("/tmp/image2.jpg"))
                .ofContentType("image/jpeg").withExtractionLevel((short) 1).build();
    }

    @Test
    public void test_records_single_file_entry_for_embedded() throws Exception {
        Document doc = embedded(EMBEDDED_ID);

        recorder(false).record(doc);

        ManifestEntry entry = repository.get(ArtifactPath.dir(projectRoot(), doc.getId()), "raw");
        assertThat(entry).isNotNull();
        assertThat(entry.isComplete()).isTrue();
        assertThat(entry.contentType()).isEqualTo("image/jpeg");
        assertThat(entry.filename()).isEqualTo("image2.jpg");
    }

    @Test
    public void test_records_empty_entry_for_root() throws Exception {
        Document root = createDoc("rootrootrootroot").with(Path.of("/tmp/root.pdf"))
                .ofContentType("application/pdf").withExtractionLevel((short) 0).build();

        recorder(false).record(root);

        ManifestEntry entry = repository.get(ArtifactPath.dir(projectRoot(), root.getId()), "raw");
        assertThat(entry.status()).isEqualTo(ManifestEntryStatus.EMPTY);
    }

    @Test
    public void test_skip_if_current_leaves_existing_entry() throws Exception {
        Document doc = embedded(EMBEDDED_ID);
        Path dir = ArtifactPath.dir(projectRoot(), doc.getId());
        // A terminal entry with the same taskInput already exists -> record(force=false) must skip.
        repository.put(dir, "raw", ManifestEntry.empty(raw.taskInput()));

        recorder(false).record(doc);

        assertThat(repository.get(dir, "raw").status()).isEqualTo(ManifestEntryStatus.EMPTY);
    }

    @Test
    public void test_force_overwrites_current_entry() throws Exception {
        Document doc = embedded(EMBEDDED_ID);
        Path dir = ArtifactPath.dir(projectRoot(), doc.getId());
        repository.put(dir, "raw", ManifestEntry.empty(raw.taskInput()));

        recorder(true).record(doc);

        assertThat(repository.get(dir, "raw").filename()).isEqualTo("image2.jpg");
    }

    @Test
    public void test_no_op_when_raw_not_selected() throws Exception {
        Document doc = embedded(EMBEDDED_ID);
        ManifestRecorder recorder = new ManifestRecorder(repository, projectRoot(), List.of(), false);

        recorder.record(doc);

        assertThat(repository.get(ArtifactPath.dir(projectRoot(), doc.getId()), "raw")).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl datashare-index -am test -Dtest=ManifestRecorderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL to compile — `ManifestRecorder` does not exist.

- [ ] **Step 3: Create `ManifestRecorder.java`**

```java
package org.icij.datashare.text.artifact;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Records the raw manifest entry for a document indexed during the INDEX stage, without
 *  re-extracting: extract-lib already wrote the raw bytes during the parse, so this only writes
 *  or updates manifest.json. It produces the same entry the ARTIFACT stage would, because both go
 *  through {@link RawArtifact#entryFor} and {@link ArtifactProducer#stampTerminal}. */
public class ManifestRecorder {
    private final ManifestRepository repository;
    private final Path projectRoot;
    private final boolean force;
    private final boolean rawSelected;
    private final RawArtifact raw = new RawArtifact();

    public ManifestRecorder(ManifestRepository repository, Path projectRoot, List<Artifact> selected, boolean force) {
        this.repository = repository;
        this.projectRoot = projectRoot;
        this.force = force;
        this.rawSelected = selected.stream().anyMatch(artifact -> artifact.type() == ArtifactType.RAW);
    }

    /** Record the raw entry for a document written during indexing. No-op when raw was not among the
     *  selected types, or when a matching terminal entry already exists (unless force). */
    public void record(Document document) throws IOException {
        if (!rawSelected) {
            return;
        }
        Path docArtifactDir = ArtifactPath.dir(projectRoot, document.getId());
        if (!force) {
            ManifestEntry existing = repository.get(docArtifactDir, ArtifactType.RAW.token());
            if (existing != null && existing.isTerminal() && existing.taskInput().equals(raw.taskInput())) {
                return;
            }
        }
        repository.put(docArtifactDir, ArtifactType.RAW.token(), ArtifactProducer.stampTerminal(raw.entryFor(document)));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl datashare-index -am test -Dtest=ManifestRecorderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit** (via `/commit`)

Message: `feat(artifact): add ManifestRecorder for INDEX-time raw manifest writing`

---

### Task 3: Wire `ManifestRecorder` into `ElasticsearchSpewer`

Add an optional recorder to the spewer and invoke it after each non-duplicate document is indexed.

**Files:**
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchSpewer.java` (field near line 114; setter near line 400; call in `writeDocument` after line 144)
- Test: `datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchSpewerTest.java` (add one test)

**Interfaces:**
- Consumes: `ManifestRecorder` (Task 2), `ArtifactPath.dir`, `FilesystemManifestRepository`, `RawArtifact`.
- Produces: `public ElasticsearchSpewer setManifestRecorder(ManifestRecorder recorder)` (returns `this`); `writeDocument` calls `recorder.record(document)` for non-duplicate documents.

- [ ] **Step 1: Write the failing test**

Add to `ElasticsearchSpewerTest.java` (imports needed: `org.icij.datashare.text.artifact.*`, `org.icij.datashare.text.indexing.elasticsearch.ArtifactPath` is same package, `org.junit.Rule`, `org.junit.rules.TemporaryFolder`, `java.util.List`):

```java
    @Rule public TemporaryFolder artifactDir = new TemporaryFolder();

    @Test
    public void test_write_records_raw_manifest_when_recorder_set() throws Exception {
        Path projectRoot = artifactDir.getRoot().toPath().resolve("prj");
        ManifestRepository repository = new FilesystemManifestRepository();
        spewer.setManifestRecorder(new ManifestRecorder(repository, projectRoot, List.of(new RawArtifact()), false));
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("recorded-file.txt"));
        document.setReader(new ParsingReader(new ByteArrayInputStream("test".getBytes())));

        spewer.write(document);

        // A root document (level 0) has no payload in its own dir, so an EMPTY entry is recorded.
        ManifestEntry entry = repository.get(ArtifactPath.dir(projectRoot, document.getId()), "raw");
        assertThat(entry).isNotNull();
        assertThat(entry.status()).isEqualTo(ManifestEntryStatus.EMPTY);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl datashare-index -am test -Dtest=ElasticsearchSpewerTest#test_write_records_raw_manifest_when_recorder_set -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL to compile — `setManifestRecorder` does not exist.

- [ ] **Step 3: Add the field**

In `ElasticsearchSpewer.java`, after the existing field declarations (after `public String indexName;` at line 114), add:

```java
    private ManifestRecorder manifestRecorder;
```

Add imports at the top: `import org.icij.datashare.text.artifact.ManifestRecorder;`

- [ ] **Step 4: Add the setter**

In `ElasticsearchSpewer.java`, add near the other configuration methods (after `configure`, around line 405):

```java
    /** Opt-in: when set, records the raw manifest entry for each indexed document (INDEX-time
     *  artifact generation). Null by default, so indexing is unchanged unless --artifacts is set. */
    public ElasticsearchSpewer setManifestRecorder(ManifestRecorder manifestRecorder) {
        this.manifestRecorder = manifestRecorder;
        return this;
    }
```

- [ ] **Step 5: Invoke the recorder in `writeDocument`**

In `ElasticsearchSpewer.java`, in the non-duplicate `else` branch, immediately after `indexer.add(indexName, document);` (line 144) and before the `String queueEntry = ...` line, add:

```java
                if (manifestRecorder != null) {
                    manifestRecorder.record(document);
                }
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -pl datashare-index -am test -Dtest=ElasticsearchSpewerTest#test_write_records_raw_manifest_when_recorder_set -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 7: Run the full spewer test to confirm no regression**

Run: `mvn -pl datashare-index -am test -Dtest=ElasticsearchSpewerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (all tests). The spewer is a per-test-method instance, so the recorder does not leak between tests.

- [ ] **Step 8: Commit** (via `/commit`)

Message: `feat(artifact): record raw manifest inline during indexing via spewer hook`

---

### Task 4: picocli `--artifacts` / `--artifactsForce`

Add the two options to the stage-run picocli surface, mirroring the JOpt semantics.

**Files:**
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/command/PipelineOptions.java`
- Test: `datashare-cli/src/test/java/org/icij/datashare/cli/command/StageRunCommandTest.java` (add tests)

**Interfaces:**
- Consumes: `DatashareCliOptions.ARTIFACTS_OPT`, `DatashareCliOptions.ARTIFACTS_FORCE_OPT` (already imported via `import static ...DatashareCliOptions.*`), `DatashareOptions.putIfNotNull`, `DatashareOptions.put`.
- Produces: parsed properties key `artifacts` (present only when flag given; `"true"` for bare, CSV otherwise) and `artifactsForce` (`"true"`/`"false"`, default `"false"`).

- [ ] **Step 1: Write the failing tests**

Add to `StageRunCommandTest.java`:

```java
    @Test
    public void test_artifacts_bare_flag_selects_all() {
        Properties props = parse("stage", "run", "--stages", "INDEX", "--artifacts");
        assertThat(props).includes(entry("artifacts", "true"));
    }

    @Test
    public void test_artifacts_csv_subset() {
        Properties props = parse("stage", "run", "--stages", "INDEX", "--artifacts", "raw,structure");
        assertThat(props).includes(entry("artifacts", "raw,structure"));
    }

    @Test
    public void test_artifacts_absent_by_default() {
        Properties props = parse("stage", "run", "--stages", "INDEX");
        assertThat(props.containsKey("artifacts")).isFalse();
    }

    @Test
    public void test_artifacts_force_defaults_to_false() {
        Properties props = parse("stage", "run", "--stages", "INDEX");
        assertThat(props).includes(entry("artifactsForce", "false"));
    }

    @Test
    public void test_artifacts_force_bare_flag_is_true() {
        Properties props = parse("stage", "run", "--stages", "INDEX", "--artifactsForce");
        assertThat(props).includes(entry("artifactsForce", "true"));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl datashare-cli -am test -Dtest=StageRunCommandTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `artifacts` / `artifactsForce` keys absent (unknown option or missing key).

- [ ] **Step 3: Add the option fields**

In `PipelineOptions.java`, add after the `artifactDir` field (line 20):

```java
    // arity = "0..1" + fallbackValue = "true" mirrors the JOpt withOptionalArg semantics: a bare
    // --artifacts means "all types", --artifacts=raw,structure selects a subset. Left null when
    // absent so toProperties omits the key and INDEX produces no artifacts.
    @Option(names = {"--artifacts"}, arity = "0..1", fallbackValue = "true",
            description = "Artifact types to produce, comma-separated (bare flag = all types); unknown types are rejected.")
    String artifacts;

    @Option(names = {"--artifactsForce"}, arity = "0..1", fallbackValue = "true", defaultValue = "false",
            description = "Reprocess artifacts even when an up-to-date manifest entry exists (bypasses caching).")
    boolean artifactsForce;
```

- [ ] **Step 4: Map them in `toProperties()`**

In `PipelineOptions.java`, add inside `toProperties()` next to the `ARTIFACT_DIR_OPT` line (after line 97):

```java
        DatashareOptions.putIfNotNull(props, ARTIFACTS_OPT, artifacts);
        DatashareOptions.put(props, ARTIFACTS_FORCE_OPT, artifactsForce);
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -pl datashare-cli -am test -Dtest=StageRunCommandTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (existing + 5 new tests).

- [ ] **Step 6: Commit** (via `/commit`)

Message: `feat(cli): add --artifacts and --artifactsForce to stage-run picocli options`

---

### Task 5: INDEX guard + recorder construction, and ARTIFACT guard test

Fail fast when `--artifacts` is set without `--artifactDir`, and build + attach the `ManifestRecorder` in `IndexTask`. Add guard tests for both stages.

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/IndexTask.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/IndexTaskTest.java` (add test)
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactTaskTest.java` (add test)

**Interfaces:**
- Consumes: `propertiesProvider.get(ARTIFACTS_OPT|ARTIFACT_DIR_OPT|ARTIFACTS_FORCE_OPT|DEFAULT_PROJECT_OPT)`, `ArtifactRegistry.select(String)`, `RawArtifact`, `FilesystemManifestRepository`, `ManifestRecorder` (Task 2), `ElasticsearchSpewer.setManifestRecorder` (Task 3), `Project.project(String)`.
- Produces: `IndexTask` constructor throws `IllegalArgumentException("--artifacts requires --artifactDir")` when `--artifacts` is set without `--artifactDir`; otherwise attaches a `ManifestRecorder` to the spewer.

- [ ] **Step 1: Write the failing guard test (INDEX)**

Add to `IndexTaskTest.java`:

```java
    @Test(expected = IllegalArgumentException.class)
    public void test_artifacts_without_artifact_dir_fails() throws Exception {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);
        Mockito.when(spewer.configure(Mockito.any())).thenReturn(spewer);
        new IndexTask(spewer, mock(DocumentCollectionFactory.class),
                new Task<>(IndexTask.class.getName(), nullUser(), new HashMap<>() {{
                    put("queueName", "test:queue");
                    put("artifacts", "true");
                }}), null);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl datashare-app -am test -Dtest=IndexTaskTest#test_artifacts_without_artifact_dir_fails -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — no exception thrown (test expects `IllegalArgumentException`).

- [ ] **Step 3: Add guard + recorder construction in `IndexTask.java`**

Add imports:

```java
import org.icij.datashare.text.Project;
import org.icij.datashare.text.artifact.Artifact;
import org.icij.datashare.text.artifact.ArtifactRegistry;
import org.icij.datashare.text.artifact.FilesystemManifestRepository;
import org.icij.datashare.text.artifact.ManifestRecorder;
import org.icij.datashare.text.artifact.RawArtifact;
import java.util.List;
```

In the constructor, insert the following block immediately before the line `Options<String> allTaskOptions = options().createFrom(Options.from(taskView.args));` (so it fails fast before the ES index is created):

```java
        propertiesProvider.get(ARTIFACTS_OPT).ifPresent(artifactsValue -> {
            String dir = propertiesProvider.get(ARTIFACT_DIR_OPT)
                    .orElseThrow(() -> new IllegalArgumentException("--artifacts requires --artifactDir"));
            Project project = Project.project(propertiesProvider.get(DEFAULT_PROJECT_OPT).orElse(DEFAULT_DEFAULT_PROJECT));
            Path projectRoot = Path.of(dir).resolve(project.name);
            List<Artifact> selected = new ArtifactRegistry(List.of(new RawArtifact())).select(artifactsValue);
            boolean force = Boolean.parseBoolean(propertiesProvider.get(ARTIFACTS_FORCE_OPT).orElse("false"));
            spewer.setManifestRecorder(new ManifestRecorder(new FilesystemManifestRepository(), projectRoot, selected, force));
        });
```

Note: `ARTIFACTS_OPT`, `ARTIFACT_DIR_OPT`, `ARTIFACTS_FORCE_OPT`, and `DEFAULT_DEFAULT_PROJECT` are already available via the existing `import static org.icij.datashare.cli.DatashareCliOptions.*;`. `DEFAULT_PROJECT_OPT` is already imported from `PropertiesProvider`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl datashare-app -am test -Dtest=IndexTaskTest#test_artifacts_without_artifact_dir_fails -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Run the full IndexTaskTest to confirm no regression**

Run: `mvn -pl datashare-app -am test -Dtest=IndexTaskTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. Existing tests pass no `artifacts` key, so the new block is skipped and behavior is unchanged.

- [ ] **Step 6: Add the ARTIFACT guard test**

`ArtifactTask` already throws when `artifactDir` is missing (it is unconditionally required). Add an explicit test to `ArtifactTaskTest.java` that pins the `--artifacts`-without-`--artifactDir` case:

```java
    @Test(expected = IllegalArgumentException.class)
    public void test_artifacts_without_artifact_dir_fails() {
        new ArtifactTask(factory, mockEs, new PropertiesProvider(Map.of("artifacts", "true")),
                new Task<>(ArtifactTask.class.getName(), User.local(), Map.of()), null);
    }
```

- [ ] **Step 7: Run the ARTIFACT guard test**

Run: `mvn -pl datashare-app -am test -Dtest=ArtifactTaskTest#test_artifacts_without_artifact_dir_fails -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (the existing `orElseThrow` on `ARTIFACT_DIR_OPT` fires).

- [ ] **Step 8: Commit** (via `/commit`)

Message: `feat(artifact): wire INDEX-stage artifact recording and guard --artifacts on --artifactDir`

---

### Task 6: Full-suite verification

**Files:** none (verification only).

- [ ] **Step 1: Run all touched modules' artifact + task tests**

Run: `mvn -pl datashare-index,datashare-cli,datashare-app -am test -Dtest=RawArtifactTest,ManifestRecorderTest,ElasticsearchSpewerTest,StageRunCommandTest,IndexTaskTest,ArtifactTaskTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS across all listed classes.

- [ ] **Step 2: Manual end-to-end parity check (INDEX vs ARTIFACT)**

Automated end-to-end ES cross-stage parity is not a coded task: INDEX and ARTIFACT both build the entry through `RawArtifact.entryFor` + `ArtifactProducer.stampTerminal` and write to the same `ArtifactPath` layout, so parity holds by construction and is exercised by `ManifestRecorderTest` (entry shape) and `ArtifactProducerTest` (stamping) plus `ElasticsearchSpewerTest` (wiring). To confirm end-to-end once, on a fixture with an embedded document:

```bash
# INDEX with artifacts inline
./run.sh stage run --stages SCAN,INDEX -P local-datashare \
  --elasticsearchAddress http://elasticsearch:9200 --artifactDir /tmp/idx --artifacts
# ARTIFACT stage over the same index into a separate dir
./run.sh stage run --stages ENQUEUEIDX,ARTIFACT -P local-datashare \
  --elasticsearchAddress http://elasticsearch:9200 --artifactDir /tmp/art
# Compare the two manifest trees (raw entries should be identical)
diff <(find /tmp/idx -name manifest.json | sort) <(find /tmp/art -name manifest.json | sort)
```

Expected: matching relative manifest paths with equal `raw` entries. Record the result in the PR description.
