# ARTIFACT & Embedded Extraction Reliability Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a verification harness proving every indexed embedded document is retrievable, audit the three-link retrieval chain for silent-loss/hang/crash modes, and fix the criticals.

**Architecture:** Three phases. Phase 1 builds the harness (nasty corpus builder, `ArtifactCoverageChecker`, end-to-end + chaos integration tests, standalone runner). Phase 2 runs the structured code audit (datashare + extract-lib) and local metric runs on the real confidential corpora, producing a severity-ranked findings report. Phase 3 fixes criticals: one pre-evidenced fix (failure accounting) is fully planned; remaining fixes are appended to this plan as an addendum after the findings report is approved.

**Tech Stack:** Java 17, Maven multi-module, JUnit 4 + fest-assert + Mockito (existing style), Elasticsearch integration via `ElasticsearchRule`, Tika/extract-lib.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-15-artifact-embedded-extraction-audit-design.md`. Read it before starting any task.
- **Confidential corpora**: `<corpus-A>` and `<corpus-B>` are real PST/OST data. NEVER use them in committed tests, never copy them into any repo, never derive fixtures from them, never quote their document content anywhere. Local manual runs and aggregate metrics only.
- Commits: use the `/commit` skill conventions; semantic messages (`feat:`, `fix:`, `test:`, `chore:`); NO Claude credit, no co-author trailers.
- Test command pattern: `mvn -pl datashare-app -am test -Dtest=<ClassName> -Dsurefire.failIfNoSpecifiedTests=false` (for datashare-index classes use `-pl datashare-index -am`). Elasticsearch is at `http://elasticsearch:9200`, Redis at `redis:6379` in the devenv; `ElasticsearchRule` uses them.
- extract-lib lives at `../extract` (module `extract-lib`). Fixes there go on their own branches in that repo.
- Datashare work happens on branch `audit/artifact-embedded-reliability` off `main` (create it in Task 1). One branch per fix concern in Phase 3.
- Follow existing code style: JUnit 4 (`org.junit.Test`), `org.fest.assertions.Assertions.assertThat`, no new dependencies.

---

## Phase 1: Verification harness

### Task 1: Nasty corpus builder

**Files:**
- Create: `datashare-app/src/test/java/org/icij/datashare/tasks/NastyCorpus.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/NastyCorpusTest.java`

**Interfaces:**
- Consumes: nothing (JDK zip/Base64 only; commons-compress for tar.gz, already on the test classpath via Tika).
- Produces: `class NastyCorpus` with `public static List<Path> buildInto(Path dir) throws IOException` returning the list of top-level files created: `deep.eml` (eml → zip → zip → txt, 3 embedding levels), `wide.zip` (200 small txt entries), `corrupt.zip` (one valid txt + one 1KiB garbage `.docx`), `archive.tar.gz` (2 txt files), `big.bin` (8 MiB deterministic bytes), `ocr_image.pdf` (a PDF whose only text is drawn inside an embedded image, so its child doc exists only when OCR ran — the spec's OCR-only shape). Deterministic content (seeded `Random(42)`, fixed strings) so digests are stable across runs, except `ocr_image.pdf` (PDF serialization embeds timestamps; it is excluded from the byte-identity assertion).

- [ ] **Step 1: Create branch**

```bash
git checkout main && git pull --ff-only && git checkout -b audit/artifact-embedded-reliability
```

- [ ] **Step 2: Write the failing test**

```java
package org.icij.datashare.tasks;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.fest.assertions.Assertions.assertThat;

public class NastyCorpusTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void builds_all_top_level_files_deterministically() throws Exception {
        Path dir = tmp.getRoot().toPath();
        List<Path> files = NastyCorpus.buildInto(dir);

        assertThat(files.stream().map(p -> p.getFileName().toString()).toList())
                .contains("deep.eml", "wide.zip", "corrupt.zip", "archive.tar.gz", "big.bin", "ocr_image.pdf");
        // deterministic: rebuilding elsewhere yields byte-identical files (stable digests)
        Path dir2 = tmp.newFolder("again").toPath();
        NastyCorpus.buildInto(dir2);
        assertThat(Files.readAllBytes(dir2.resolve("big.bin")))
                .isEqualTo(Files.readAllBytes(dir.resolve("big.bin")));
        assertThat(Files.readAllBytes(dir2.resolve("deep.eml")))
                .isEqualTo(Files.readAllBytes(dir.resolve("deep.eml")));
    }

    @Test
    public void wide_zip_has_200_entries_and_corrupt_zip_keeps_valid_sibling() throws Exception {
        Path dir = tmp.getRoot().toPath();
        NastyCorpus.buildInto(dir);

        int wideEntries = 0;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(dir.resolve("wide.zip")))) {
            while (zis.getNextEntry() != null) wideEntries++;
        }
        assertThat(wideEntries).isEqualTo(200);

        boolean hasValidSibling = false, hasGarbage = false;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(dir.resolve("corrupt.zip")))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().equals("sibling-ok.txt")) hasValidSibling = true;
                if (e.getName().equals("broken.docx")) hasGarbage = true;
            }
        }
        assertThat(hasValidSibling).isTrue();
        assertThat(hasGarbage).isTrue();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -pl datashare-app -am test -Dtest=NastyCorpusTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR, `NastyCorpus` does not exist.

- [ ] **Step 4: Write the implementation**

```java
package org.icij.datashare.tasks;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Deterministic corpus of embedding shapes known to stress extraction:
 *  deep nesting, wide fan-out, corrupt member among valid siblings, archive, big binary.
 *  Confidential real corpora must NEVER be added here (see spec). */
public class NastyCorpus {
    public static List<Path> buildInto(Path dir) throws IOException {
        Path deep = dir.resolve("deep.eml");
        Files.write(deep, emlWithAttachment("level1.zip",
                zip("level2.zip", zip("deep-secret.txt", "nested level 3 secret".getBytes(StandardCharsets.UTF_8)))));

        Path wide = dir.resolve("wide.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(wide))) {
            for (int i = 0; i < 200; i++) {
                zos.putNextEntry(new ZipEntry("wide-" + i + ".txt"));
                zos.write(("wide entry number " + i).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }

        Path corrupt = dir.resolve("corrupt.zip");
        byte[] garbage = new byte[1024];
        new Random(42).nextBytes(garbage);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(corrupt))) {
            zos.putNextEntry(new ZipEntry("sibling-ok.txt"));
            zos.write("the valid sibling must still be extracted".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("broken.docx"));
            zos.write(garbage);
            zos.closeEntry();
        }

        Path archive = dir.resolve("archive.tar.gz");
        try (OutputStream fos = Files.newOutputStream(archive);
             GzipCompressorOutputStream gzos = new GzipCompressorOutputStream(fos);
             TarArchiveOutputStream taos = new TarArchiveOutputStream(gzos)) {
            for (int i = 0; i < 2; i++) {
                byte[] content = ("tar member " + i).getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry entry = new TarArchiveEntry("member-" + i + ".txt");
                entry.setSize(content.length);
                taos.putArchiveEntry(entry);
                taos.write(content);
                taos.closeArchiveEntry();
            }
        }

        Path big = dir.resolve("big.bin");
        byte[] bigBytes = new byte[8 * 1024 * 1024];
        new Random(4242).nextBytes(bigBytes);
        Files.write(big, bigBytes);

        Path ocr = dir.resolve("ocr_image.pdf");
        writeOcrOnlyPdf(ocr);

        return List.of(deep, wide, corrupt, archive, big, ocr);
    }

    // PDFBox is on the test classpath via tika-parsers. Adjust imports to the version present
    // (2.x: org.apache.pdfbox.pdmodel.*, graphics.image.LosslessFactory).
    private static void writeOcrOnlyPdf(Path target) throws IOException {
        try (org.apache.pdfbox.pdmodel.PDDocument pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            pdf.addPage(page);
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(400, 120, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, 400, 120);
            g.setColor(java.awt.Color.BLACK);
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 32));
            g.drawString("OCR ONLY TEXT", 20, 70);
            g.dispose();
            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject pdImage =
                    org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(pdf, img);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
                cs.drawImage(pdImage, 100, 500);
            }
            pdf.save(target.toFile());
        }
    }

    private static byte[] zip(String entryName, byte[] content) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private static byte[] emlWithAttachment(String filename, byte[] attachment) {
        String boundary = "----=_NastyCorpusBoundary";
        String eml = "From: audit@example.org\r\n"
                + "To: audit@example.org\r\n"
                + "Subject: deep nesting fixture\r\n"
                + "Date: Wed, 15 Jul 2026 10:00:00 +0000\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=\"" + boundary + "\"\r\n\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n\r\n"
                + "body with a nested zip attachment\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: application/zip; name=\"" + filename + "\"\r\n"
                + "Content-Disposition: attachment; filename=\"" + filename + "\"\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\n"
                + Base64.getMimeEncoder().encodeToString(attachment) + "\r\n"
                + "--" + boundary + "--\r\n";
        return eml.getBytes(StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -pl datashare-app -am test -Dtest=NastyCorpusTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (2 tests). If `commons-compress` is not resolvable in test scope, add nothing: it is a Tika transitive dependency; instead verify with `mvn -pl datashare-app dependency:tree -Dincludes=org.apache.commons:commons-compress` and report if absent (that would be a plan deviation to flag, not silently work around).

- [ ] **Step 6: Commit**

```bash
git add datashare-app/src/test/java/org/icij/datashare/tasks/NastyCorpus.java datashare-app/src/test/java/org/icij/datashare/tasks/NastyCorpusTest.java
git commit -m "test(artifact): add deterministic nasty corpus builder for embedded-extraction audits"
```

---

### Task 2: ArtifactCoverageChecker

**Files:**
- Create: `datashare-index/src/main/java/org/icij/datashare/text/artifact/ArtifactCoverageChecker.java`
- Test: `datashare-index/src/test/java/org/icij/datashare/text/artifact/ArtifactCoverageCheckerTest.java`

**Interfaces:**
- Consumes: `Indexer` (`indexer.search(List.of(projectName), Document.class)` + `.withoutSource("content", "content_translated").limit(n).scroll(...)` — mirror the scroll usage in `EnqueueFromIndexTask.call()` at `datashare-app/src/main/java/org/icij/datashare/tasks/EnqueueFromIndexTask.java:70-96`), `FilesystemManifestRepository.get(Path docArtifactDir, String token)`, `ArtifactPath.dir(Path projectRoot, String digest)`, `SourceExtractor.getSource(Project, Document)`, `ManifestEntry.isTerminal()`.
- Produces:
  - `record Hole(String docId, String rootId, String reason)`
  - `record Report(long checked, List<Hole> holes)` with `boolean complete()` (true iff `holes.isEmpty()`) and `String summary()` (one line per hole, capped at 50 with a "and N more" tail; never silent truncation).
  - `public ArtifactCoverageChecker(Indexer indexer, SourceExtractor extractor)` and `public Report check(Project project, Path artifactDir, int scrollSize)`.

**Semantics (from spec, with one precision):** for every document in the index, assert (a) a terminal manifest entry exists for token `"raw"` in `ArtifactPath.dir(artifactDir.resolve(project.name), docId)` (EMPTY is terminal and valid for roots), and (b) `SourceExtractor.getSource(project, document)` returns a non-empty byte stream. Digest correctness of the returned bytes is enforced by the extraction machinery itself (a digest mismatch surfaces as `ContentNotFoundException`, which the checker records as a hole), so the checker does not recompute salted digests.

- [ ] **Step 1: Write the failing test**

Use the existing fixture `datashare-index/src/test/resources/docs/embedded_doc.eml` and the known digests from `ArtifactTaskTest` (root `0f95ef97e4619f7bae2a585c6cf24587cd7a3a81a26599c8774d669e5c175e5e`, embedded child `6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e`). Mock the `Indexer` scroll to return two `Document` objects (build them the way `datashare-index` tests build documents; see `DocumentBuilder` usages in that module — `DocumentBuilder.createDoc(...)`). Three cases:

The unit tests exercise the `Stream<Document>` variant directly (no ES, no Indexer mock gymnastics); the scroll variant is covered by Task 3's integration test. Stub `SourceExtractor` by subclassing (the pattern used throughout `ArtifactTaskTest`).

```java
package org.icij.datashare.text.artifact;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;

public class ArtifactCoverageCheckerTest {
    private static final String DOC_ID = "6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e";
    @Rule public TemporaryFolder artifactDir = new TemporaryFolder();
    private final Project prj = project("prj");
    private final PropertiesProvider props = new PropertiesProvider(Map.of("defaultProject", "prj"));

    private SourceExtractor extractorReturning(byte[] bytes) {
        return new SourceExtractor(props) {
            @Override public InputStream getSource(Project project, Document document) {
                return new ByteArrayInputStream(bytes);
            }
        };
    }

    private void seedTerminalManifest(String docId) throws Exception {
        Path docDir = ArtifactPath.dir(artifactDir.getRoot().toPath().resolve("prj"), docId);
        new FilesystemManifestRepository().put(docDir, "raw",
                ManifestEntry.singleFile(Map.of(), "text/plain", "raw").withStatus(ManifestEntryStatus.COMPLETE));
    }

    @Test
    public void reports_complete_when_manifest_terminal_and_source_streams() throws Exception {
        seedTerminalManifest(DOC_ID);
        Document doc = createDoc(DOC_ID).with(prj).build();

        ArtifactCoverageChecker.Report report = new ArtifactCoverageChecker(null, extractorReturning("payload".getBytes()))
                .check(prj, artifactDir.getRoot().toPath(), Stream.of(doc));

        assertThat(report.complete()).isTrue();
        assertThat(report.checked()).isEqualTo(1);
    }

    @Test
    public void reports_hole_when_manifest_missing() {
        Document doc = createDoc(DOC_ID).with(prj).build();

        ArtifactCoverageChecker.Report report = new ArtifactCoverageChecker(null, extractorReturning("payload".getBytes()))
                .check(prj, artifactDir.getRoot().toPath(), Stream.of(doc));

        assertThat(report.complete()).isFalse();
        assertThat(report.holes().get(0).reason()).contains("manifest");
    }

    @Test
    public void reports_hole_when_getSource_throws_or_streams_zero_bytes() throws Exception {
        seedTerminalManifest(DOC_ID);
        Document doc = createDoc(DOC_ID).with(prj).build();
        SourceExtractor throwing = new SourceExtractor(props) {
            @Override public InputStream getSource(Project project, Document document) {
                throw new RuntimeException("digest never matched");
            }
        };

        ArtifactCoverageChecker.Report failed = new ArtifactCoverageChecker(null, throwing)
                .check(prj, artifactDir.getRoot().toPath(), Stream.of(doc));
        ArtifactCoverageChecker.Report empty = new ArtifactCoverageChecker(null, extractorReturning(new byte[0]))
                .check(prj, artifactDir.getRoot().toPath(), Stream.of(doc));

        assertThat(failed.holes().get(0).reason()).contains("digest never matched");
        assertThat(empty.holes().get(0).reason()).contains("empty");
    }
}
```

Adjust `createDoc(...)` chaining to `DocumentBuilder`'s real API if it differs (it lives in `datashare-api`; `EnqueueFromIndexTaskTest` shows `createDoc("id").with(project(...)).build()`). If `FilesystemManifestRepository.put`'s exact signature differs from `(Path, String, ManifestEntry)`, follow `FilesystemManifestRepositoryTest` — do not change the production class to fit the plan.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl datashare-index -am test -Dtest=ArtifactCoverageCheckerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR (class missing).

- [ ] **Step 3: Implement the checker**

```java
package org.icij.datashare.text.artifact;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Post-run completeness audit: every document in the index must have a terminal raw
 *  manifest entry and a servable source. Reusable standalone against real projects. */
public class ArtifactCoverageChecker {
    public record Hole(String docId, String rootId, String reason) {}
    public record Report(long checked, List<Hole> holes) {
        public boolean complete() { return holes.isEmpty(); }
        public String summary() {
            StringBuilder sb = new StringBuilder(String.format("checked %d document(s), %d hole(s)%n", checked, holes.size()));
            holes.stream().limit(50).forEach(h -> sb.append(String.format("  %s (root %s): %s%n", h.docId(), h.rootId(), h.reason())));
            if (holes.size() > 50) sb.append(String.format("  ... and %d more%n", holes.size() - 50));
            return sb.toString();
        }
    }

    private final FilesystemManifestRepository manifests = new FilesystemManifestRepository();
    private final Indexer indexer;
    private final SourceExtractor extractor;

    public ArtifactCoverageChecker(Indexer indexer, SourceExtractor extractor) {
        this.indexer = indexer;
        this.extractor = extractor;
    }

    public Report check(Project project, Path artifactDir, Stream<Document> documents) {
        Path projectRoot = artifactDir.resolve(project.name);
        List<Hole> holes = new ArrayList<>();
        long[] checked = {0};
        documents.forEach(doc -> {
            checked[0]++;
            try {
                ManifestEntry raw = manifests.get(ArtifactPath.dir(projectRoot, doc.getId()), "raw");
                if (raw == null || !raw.isTerminal()) {
                    holes.add(new Hole(doc.getId(), doc.getRootDocument(), "no terminal raw manifest entry"));
                    return; // manifest hole recorded; still try the source? no: getSource may mask it
                }
                try (InputStream source = extractor.getSource(project, doc)) {
                    if (source.read() == -1) {
                        holes.add(new Hole(doc.getId(), doc.getRootDocument(), "source stream is empty"));
                    }
                }
            } catch (Exception e) {
                holes.add(new Hole(doc.getId(), doc.getRootDocument(), e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        });
        return new Report(checked[0], List.copyOf(holes));
    }

    /** Scroll the whole index (mirrors EnqueueFromIndexTask's scroll loop) and check it. */
    public Report check(Project project, Path artifactDir, int scrollSize) throws Exception {
        Indexer.Searcher searcher = indexer.search(List.of(project.name), Document.class)
                .withoutSource("content", "content_translated").limit(scrollSize);
        long checked = 0;
        List<Hole> holes = new ArrayList<>();
        List<? extends org.icij.datashare.Entity> batch = searcher.scroll("60s").toList();
        while (!batch.isEmpty()) {
            Report partial = check(project, artifactDir, batch.stream().map(e -> (Document) e));
            checked += partial.checked();
            holes.addAll(partial.holes());
            batch = searcher.scroll("60s").toList();
        }
        searcher.clearScroll();
        return new Report(checked, List.copyOf(holes));
    }
}
```

The scroll loop mirrors `EnqueueFromIndexTask.call()` (`EnqueueFromIndexTask.java:70-96`); if the fluent searcher API differs from this sketch (e.g. `withoutSource` name, scroll duration type), match the real API from that file rather than this plan.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl datashare-index -am test -Dtest=ArtifactCoverageCheckerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add datashare-index/src/main/java/org/icij/datashare/text/artifact/ArtifactCoverageChecker.java datashare-index/src/test/java/org/icij/datashare/text/artifact/ArtifactCoverageCheckerTest.java
git commit -m "feat(artifact): add ArtifactCoverageChecker auditing manifest and source completeness"
```

---

### Task 3: End-to-end pipeline coverage integration test

**Files:**
- Create: `datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactCoverageIntTest.java`

**Interfaces:**
- Consumes: `NastyCorpus.buildInto(Path)` (Task 1), `ArtifactCoverageChecker` (Task 2), `ElasticsearchRule`, `IndexTask` + `ElasticsearchSpewer` (mirror `IndexTaskIntTest.java:52-74` setup exactly), `EnqueueFromIndexTask` (mirror `EnqueueFromIndexTaskTest` for properties/queue naming — read it first), `ArtifactTask`.
- Produces: the executable definition of "the task can be relied on". A failure of this test on a corpus shape is an AUDIT FINDING (record it in the findings file of Task 8), not a broken test: keep the assertion, and document the failing shape in the findings report with the checker's `summary()` output.

- [ ] **Step 1: Write the test**

Property/queue conventions come from the two reference tests: `IndexTaskIntTest.java:52-74` (INDEX with real `ElasticsearchSpewer`, `Refresh.True`, input queue named via `PipelineHelper.getQueueNameFor(Stage.INDEX)`) and `EnqueueFromIndexTaskTest.test_no_query_targeting_artifact_ignores_nlp_pipeline_filter` (lines 87-103: `stages = "ARTIFACT,ENQUEUEIDX"` + `queueName = "test:queue"` routes the enqueue output to `test:queue:artifact`).

```java
public class ArtifactCoverageIntTest {
    @Rule public ElasticsearchRule es = new ElasticsearchRule();
    @Rule public TemporaryFolder corpusDir = new TemporaryFolder();
    @Rule public TemporaryFolder artifactDir = new TemporaryFolder();

    @Test(timeout = 300_000)
    public void every_indexed_document_has_a_terminal_manifest_and_servable_source() throws Exception {
        Map<String, Object> map = new HashMap<>(Map.of(
                "defaultProject", es.getIndexName(),
                "stages", "ARTIFACT,ENQUEUEIDX",
                "queueName", "test:queue",
                "artifactDir", artifactDir.getRoot().toString(),
                "pollingInterval", "1",
                "parallelism", "2"));
        PropertiesProvider props = new PropertiesProvider(map);

        // 1. corpus on the INDEX queue
        MemoryDocumentCollectionFactory<Path> indexQueueFactory = new MemoryDocumentCollectionFactory<>();
        MemoryDocumentCollectionFactory<String> stringQueueFactory = new MemoryDocumentCollectionFactory<>();
        DocumentQueue<Path> indexQueue = indexQueueFactory.createQueue(
                new PipelineHelper(props).getQueueNameFor(Stage.INDEX), Path.class);
        NastyCorpus.buildInto(corpusDir.getRoot().toPath()).forEach(indexQueue::add);

        // 2. INDEX with a real spewer (same construction as IndexTaskIntTest:59-60)
        ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);
        ElasticsearchSpewer spewer = new ElasticsearchSpewer(indexer, stringQueueFactory,
                text -> Language.ENGLISH, new FieldNames(), props);
        new IndexTask(spewer, indexQueueFactory, new Task<>(IndexTask.class.getName(), User.local(), map), null).call();

        // 3. ENQUEUEIDX -> ARTIFACT (queue test:queue:artifact), then the task itself
        new EnqueueFromIndexTask(stringQueueFactory, indexer,
                new Task<>(EnqueueFromIndexTask.class.getName(), User.local(), map), null).call();
        new ArtifactTask(stringQueueFactory, indexer, props,
                new Task<>(ArtifactTask.class.getName(), User.local(), map), null).call();

        // 4. coverage
        ArtifactCoverageChecker.Report report = new ArtifactCoverageChecker(indexer, new SourceExtractor(props))
                .check(Project.project(es.getIndexName()), artifactDir.getRoot().toPath(), 100);
        assertThat(report.checked()).isGreaterThan(200); // wide.zip alone contributes ~200 children
        assertThat(report.summary()).isEqualTo(String.format("checked %d document(s), 0 hole(s)%n", report.checked()));
    }
}
```

Two things must be verified while writing, not assumed: (a) `ArtifactTask`'s input queue name under these properties is `test:queue:artifact` — the same `PipelineHelper` naming the enqueue task writes to (if not, align `queueName`/`stages` until both agree; `ArtifactTaskTest` used the default naming `extract:queue:artifact`); (b) whether `IndexTask` needs a poison pill on its input queue to terminate (see `PipelineTask.STRING_POISON` / `PATH_POISON` usage in `IndexTaskIntTest`) — add it as that test does. The assertion on `summary()` rather than `complete()` makes every hole print its reason in the failure output.

- [ ] **Step 3: Run it**

Run: `mvn -pl datashare-app -am test -Dtest=ArtifactCoverageIntTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: either PASS, or FAIL with a hole list. A FAIL here is a deliverable: copy the hole list verbatim into the findings file (Task 8 creates it; if running earlier, create `docs/superpowers/specs/2026-07-15-artifact-embedded-extraction-findings.md` with a "raw evidence" section). Do NOT weaken the assertion and do NOT mark the test `@Ignore` — leave it red on the audit branch until the Phase 3 fix lands.

- [ ] **Step 4: Commit**

```bash
git add datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactCoverageIntTest.java
git commit -m "test(artifact): end-to-end coverage test from corpus to servable source"
```

---

### Task 4: Chaos integration tests

**Files:**
- Create: `datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactChaosIntTest.java`

**Interfaces:**
- Consumes: everything Task 3 consumes, plus `ArtifactTask.cancel(boolean)` (see `ArtifactTaskTest.test_cancel_stops_an_in_flight_run` at `ArtifactTaskTest.java:308-357` for the cancel-from-another-thread pattern).
- Produces: three tests documenting the resilience contract. Same finding policy as Task 3: red tests are findings, not test bugs.

- [ ] **Step 1: Write the three tests**

1. `killed_run_then_rerun_converges_to_full_coverage`: index the corpus; run `ArtifactTask` on a caller thread and `cancel(false)` while in flight (latch inside an overridden `createSourceExtractor`, as in the existing cancel tests); then re-enqueue (fresh `EnqueueFromIndexTask` run) and run a new `ArtifactTask` to completion; assert the checker reports 0 holes AND (skip-if-current works) assert via `LogbackCapturingRule` that already-produced docs were not re-produced (`ArtifactProducer` skip path produces no "failed to produce" and the second run's produced count < total count).
2. `parallelism_4_produces_identical_coverage`: same corpus, `parallelism=4`, assert 0 holes and no manifest JSON parse errors in logs.
3. `corrupt_member_is_reported_visibly_and_siblings_are_covered`: corpus = `corrupt.zip` only; run the pipeline; assert every indexed document EXCEPT any hole caused by the garbage `.docx` is covered, and that the task's final log output contains an explicit count of production failures (this asserts the Phase 3 accounting fix; before that fix lands this test is expected RED — that is finding evidence for Task 7).

- [ ] **Step 2: Run them**

Run: `mvn -pl datashare-app -am test -Dtest=ArtifactChaosIntTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: tests 1-2 PASS or produce findings; test 3 RED until Task 10 lands (by design).

- [ ] **Step 3: Commit**

```bash
git add datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactChaosIntTest.java
git commit -m "test(artifact): chaos tests for kill-resume convergence, parallelism, corrupt members"
```

---

### Task 5: Standalone coverage runner (for real projects)

**Files:**
- Create: `datashare-index/src/main/java/org/icij/datashare/text/artifact/ArtifactCoverageMain.java`

**Interfaces:**
- Consumes: `ArtifactCoverageChecker.check(Project, Path, int)`, `ElasticsearchIndexer` construction (see how `datashare-index` builds a client from a URL — `ElasticsearchConfiguration`).
- Produces: `public static void main(String[] args)` taking `<elasticsearchUrl> <indexName> <artifactDir>`; prints `report.summary()` to stdout and exits 0 iff complete, 1 otherwise. Deliberately NOT wired into the JOpt/picocli CLI surfaces (that dual wiring is its own project; this is an audit tool). Run with:

```bash
mvn -pl datashare-index -am -q compile exec:java \
  -Dexec.mainClass=org.icij.datashare.text.artifact.ArtifactCoverageMain \
  -Dexec.args="http://elasticsearch:9200 local-datashare /path/to/artifacts"
```

- [ ] **Step 1: Implement main()** (no TDD ceremony for a 30-line arg-parse + delegate wrapper; the checker underneath is tested)

Guard: `if (args.length != 3) { System.err.println("usage: ArtifactCoverageMain <esUrl> <index> <artifactDir>"); System.exit(2); }`. Build the ES client the same way `ElasticsearchConfiguration`/`ElasticsearchIndexer` are built in tests but pointed at `args[0]`; `PropertiesProvider` with `artifactDir` and `defaultProject=args[1]` for the `SourceExtractor`.

- [ ] **Step 2: Smoke-run it against the Task 3 leftovers or a throwaway local index**

Expected: prints a summary line and hole list; exit code matches.

- [ ] **Step 3: Commit**

```bash
git add datashare-index/src/main/java/org/icij/datashare/text/artifact/ArtifactCoverageMain.java
git commit -m "feat(artifact): standalone coverage audit runner for real projects"
```

---

## Phase 2: Audit

### Task 6: Real-corpora metric runs (LOCAL ONLY — nothing committed)

**Files:**
- Create (NOT committed, scratch only): metric notes under the session scratchpad, aggregated conclusions go into the findings file (Task 8).

**Interfaces:**
- Consumes: the built datashare CLI (`datashare-dist` or `mvn -pl datashare-cli ... exec`), `ArtifactCoverageMain` (Task 5).
- Produces: aggregate metrics feeding the findings report: per-stage durations, per-document production times (p50/p95/max), hang candidates (documents where production exceeded N minutes), tmp-dir growth, heap behavior, checker hole counts. AGGREGATES ONLY: no document names, subjects, or content in anything written down.

- [ ] **Step 1: Run SCAN+INDEX then ENQUEUEIDX+ARTIFACT on `<corpus-A>`** into a throwaway local ES index (e.g. `audit-corpus-a`) and artifact dir under the scratchpad. Capture logs to files. Watch `/tmp` usage and heap (`jcmd <pid> GC.heap_info` samples) during the run.
- [ ] **Step 2: Same for `<corpus-B>`** (index `audit-corpus-b`).
- [ ] **Step 3: Run `ArtifactCoverageMain` against both** and save the two summaries (hole counts + reasons, doc ids are digests so they may be recorded).
- [ ] **Step 4: Extract metrics from logs** (grep ERROR/WARN classes, timestamp deltas around each "Extracting embedded document" line) into an aggregate table. Any document whose production stalls the worker for more than 10 minutes is a hang finding: capture a thread dump (`jstack <pid>`) at that moment.
- [ ] **Step 5: Clean up**: delete the throwaway indices and scratch artifact dirs when done, confirm nothing under either repo references the corpora.

### Task 7: Structured code audit of the three links

**Files:**
- Modify: none (read-only investigation; reproducing unit tests, if any, go next to the code they exercise and are committed).
- Reference: `datashare-app/.../tasks/ArtifactTask.java`, `EnqueueFromIndexTask.java`, `datashare-index/.../elasticsearch/SourceExtractor.java`, `.../artifact/ArtifactProducer.java`, `FilesystemManifestRepository.java`, and in `../extract`: `extract-lib` classes `EmbeddedDocumentExtractor`, `EmbedSpawner` (or current equivalent), `UpdatableDigester`, `Extractor`.

Work through this checklist; each item gets a verdict (OK / FINDING sev-N) with file:line evidence, and a reproducing test where feasible:

- [ ] **Link 2 / queue lifecycle**: `ArtifactTask.runWorker` exits when `inputQueue.poll(pollingInterval, SECONDS)` returns null (`ArtifactTask.java:133`). Establish: is ENQUEUEIDX guaranteed to complete before ARTIFACT starts in every deployment mode (Temporal chaining, CLI stage order)? Is there a poison-pill entry on this queue, and what does `DocReference.parse` do with it? Can a >pollingInterval stall (slow ES `getDocument`, GC pause on a sibling worker draining the queue) make a worker exit while work remains?
- [ ] **Link 2 / accounting**: `ArtifactProducer.run` returning false is only visible as a per-doc ERROR log (`ArtifactProducer.java:54-57`); `nbDocs` is not incremented, `nbSkipped` only counts index misses (`ArtifactTask.java:136-139`). Confirm the end-of-run summary cannot distinguish "queue had fewer docs" from "N docs failed production". (Pre-confirmed by code reading; the fix is Task 10.)
- [ ] **Link 2 / per-doc timeout**: no bound around `producer.run` (`ArtifactTask.java:142`). Combined with Task 6 timing data, decide whether a per-document watchdog (log-and-thread-dump, or hard bound) is warranted, and what Tika interruption semantics allow.
- [ ] **Link 2 / memory & tmp**: `SourceExtractor.extractEmbeddedSources` (`SourceExtractor.java:120-131`) walks the whole root document per queue entry. For a container with N indexed children all N entries re-parse the same root: confirm O(N × root-parse) behavior, measure on `wide.zip` (200 children), and check what `EmbeddedDocumentExtractor.extractAll` writes per pass (does one pass cache ALL children — making the per-child re-parse mostly redundant — or only the polled one?). Check tmp-file cleanup on the exception path.
- [ ] **Link 1 / digest & config drift**: build the matrix of digest-affecting knobs at INDEX time vs ARTIFACT/download time: `digestAlgorithm`, `noDigestProject` (`SourceExtractor.java:147-149` defaults true), `defaultProject`, `charset`, Tika version pinning (`SourceExtractor.java:93-96`). For each combination that can differ between the original index run and a later retrieval, verify `getEmbeddedSource`'s three-digester fallback (`SourceExtractor.java:79-87`) actually covers it, including in SERVER mode where `mightUseLegacyDigester` is disabled (`SourceExtractor.java:151-153`).
- [ ] **Link 1 / orphaned embeds**: in extract-lib, confirm current behavior when a parse dies mid-tree (root written last? children orphaned?) against the datashare index (does `EnqueueFromIndexTask` enqueue children whose root document was never indexed, and what does `ArtifactTask` do with them?).
- [ ] **Link 1 / maxEmbedDepth**: children deeper than the ARTIFACT-time depth limit: indexed at INDEX time with one depth, retrievable at another? Locate the depth knob on both paths.
- [ ] **Link 3 / cache-hit correctness**: verify `EmbeddedDocumentExtractor.extract(rootDocument, id)` (called at `SourceExtractor.java:102`) consults the artifact cache (the `getArtifactPath(project)` argument) BEFORE re-parsing, that a cache hit does not depend on which digester iteration is running, and that a corrupt/truncated cached raw file falls back to live extraction rather than serving garbage.
- [ ] **Link 3 / failure UX**: `ContentNotFoundException` propagation to the HTTP layer: what status/message does the user get, and are the three failed digester attempts logged at a level an operator will see (currently `LOGGER.debug`, `SourceExtractor.java:108-114`)?

### Task 8: Findings report

**Files:**
- Create: `docs/superpowers/specs/2026-07-15-artifact-embedded-extraction-findings.md` (NOT committed, like all superpowers docs).

- [ ] **Step 1: Write the report**: one section per finding: severity (silent-loss > hang > crash > noise), the evidence (file:line, test name, or aggregate metric from Task 6), user-visible impact, proposed fix, target repo/branch. Include the Task 3/4 checker outputs verbatim and the Task 6 aggregate metrics table. End with the ranked fix list split into "fix now" (silent-loss, hang) and "fix plan only".
- [ ] **Step 2: Present the report to the reviewer** and get their sign-off on the "fix now" list before starting Phase 3 tasks beyond Task 10.

---

## Phase 3: Fixes

### Task 9 (gate): Plan addendum for discovered criticals

- [ ] After the reviewer approves the findings report, append one fully-specified TDD task per approved "fix now" finding to this plan file (same step granularity as Task 10 below: failing test with real code, run, minimal fix, run, commit). Datashare fixes: one branch per concern off `main` (e.g. `fix/artifact-<concern>`), cherry-picking the harness branch if needed. extract-lib fixes: branch in `../extract` off its default branch, plus the datashare-side version bump if a release is cut. Do not start an unapproved fix.

### Task 10: Failure accounting in ArtifactTask (pre-evidenced critical, silent-loss class)

The one fix already evidenced by code reading: a production failure (`ArtifactProducer.run` → false) leaves no trace in the task's final summary, so a run with holes looks successful.

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/ArtifactTask.java:82-152`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactTaskTest.java` (extend `test_per_document_failure_is_non_fatal`, lines 218-256)

**Interfaces:**
- Consumes: existing `ArtifactProducer.run(...)` boolean return.
- Produces: unchanged `Long call()` return (produced count); new end-of-run ERROR summary line `"{} document(s) failed artifact production in project {}, re-run the ARTIFACT stage with --artifactsForce for them"`; `ArtifactChaosIntTest` test 3 (Task 4) turns green.

- [ ] **Step 1: Extend the failing test** — in `test_per_document_failure_is_non_fatal`, add:

```java
assertThat(logback.logs(Level.ERROR)).contains("1 document(s) failed artifact production in project prj, re-run the ARTIFACT stage with --artifactsForce for them");
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl datashare-app -am test -Dtest=ArtifactTaskTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL on the new assertion only.

- [ ] **Step 3: Minimal implementation** — in `ArtifactTask`: add `AtomicLong nbFailed` alongside `nbDocs`/`nbSkipped` in `call()`, pass it to `runWorker`; in `runWorker`, replace the boolean-dropping call at line 142 with:

```java
if (producer.run(selected, new ArtifactContext(project, doc, docArtifactDir, extractor), force)) {
    nbDocs.incrementAndGet();
} else {
    nbFailed.incrementAndGet();
}
```

and also count the `catch (Throwable e)` branch (line 145) into `nbFailed`. In `call()`, next to the existing `nbSkipped` summary (lines 115-117):

```java
if (nbFailed.get() > 0) {
    logger.error("{} document(s) failed artifact production in project {}, re-run the ARTIFACT stage with --artifactsForce for them", nbFailed.get(), project.name);
}
```

- [ ] **Step 4: Run the full test class**

Run: `mvn -pl datashare-app -am test -Dtest=ArtifactTaskTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, all existing tests untouched.

- [ ] **Step 5: Commit (on its own branch)**

```bash
git checkout -b fix/artifact-failure-accounting audit/artifact-embedded-reliability
git add datashare-app/src/main/java/org/icij/datashare/tasks/ArtifactTask.java datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactTaskTest.java
git commit -m "fix(artifact): count and report production failures in end-of-run summary"
```

---

## Execution order & dependencies

1 → 2 → 3 → 4 → 5 (harness; strictly sequential, each consumes the previous)
6 and 7 can run after 5, in either order or interleaved (6 needs the runner from 5)
8 needs 3, 4, 6, 7 → 9 (review gate) → 10 and addendum tasks

Task 10 may run any time after Task 4 (its evidence is already established); running it early turns chaos test 3 green sooner.

---

## Phase 3 addendum (approved fixes; detail in findings report)

extract-lib branch `fix/artifact-embedded-reliability` off master (9.15.4), TDD each, own commit:
- **E-a (N4):** `EmbeddedDocumentExtractor.extract`/`extractAll` (:89,:105) wrap `new FileInputStream(root)` in try-with-resources.
- **E-b (F3):** `EmbeddedDocumentExtractor` ctor (:54-55) applies `Extractor.replaceParser(base, OutlookPSTParser.class, p -> new ResilientOutlookPSTParser())` before wrapping in DigestingParser, so retrieval matches INDEX.
- **E-c (F1/N2/F2):** `EmbeddedArtifactWriter.write` (both overloads) write raw + raw.json via unique temp + `ATOMIC_MOVE` (kills the race + truncation window; content-addressed ids => idempotent). `EmbeddedDocumentExtractor.extract` (:92-98) treats a missing/corrupt `raw.json` or absent raw as a cache MISS and falls through to live parse instead of throwing.
- **E-d (A3):** `EmbedParser.delegateParsing` aborts on `Thread.isInterrupted()` at the embed boundary, mirroring `EmbedSpawner`.
- **E-bump:** extract-lib -> 9.15.5, `mvn install`; datashare `extract.version` 9.15.3 -> 9.15.5.

datashare on `audit/artifact-embedded-reliability`, TDD each:
- **D1 (N1):** `EnqueueFromIndexTask` emits an end-of-stream signal (N poison pills after scroll); `ArtifactTask` exits on the pill, not the 60s empty-poll.
- **D2 (N3):** `SourceExtractor.extractEmbeddedSources` caching path reuses the ordered digester list; SERVER-mode read keeps the legacy digester.

DEFERRED to fix-plan (perf/UX, not correctness): dedup of the per-child full-root re-parse (once writes are atomic the redundancy is harmless-but-wasteful); A9 failure logging; A2 log attribution.

Verify: `ArtifactCoverageIntTest` + `ArtifactChaosIntTest` green at parallelism 4 across repeated runs; real-OST coverage 0 -> full (D4).
