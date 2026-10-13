# ArtifactResource (#2228) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve every per-document artifact from disk at `/:project/artifacts/<type>/:id`, driven by `manifest.json`, with one access-control decision shared with the existing download route.

**Architecture:** A new `ArtifactResource` (fluent-http, `datashare-app/web`) does HTTP only. Every manifest and layout rule lives in `ArtifactReader` (`datashare-index/text.artifact`), which resolves a page payload for both pagination schemes through `ArtifactPath` helpers. The download gate (`project grant` + download-IP + root-size) and the source payload builder move out of `DocumentResource` into `DocumentSourceAccess` so `raw` reuses them instead of copying a security rule.

**Tech Stack:** Java 21, fluent-http (`@Prefix`/`@Get`), Guice, Jackson, JUnit 4 + fest-assert + Mockito, `AbstractProdWebServerTest` + `MockIndexer` for route tests, Maven multi-module reactor.

**Spec:** `docs/superpowers/specs/2026-07-31-artifact-resource-design.md`. Read its Decisions section before Task 1.

## Global Constraints

- **Branch:** cut `feat/artifacts-resource` from `main`. Do not base on `feat/artifacts-index-stage` (#2225) or the parallel session's branch.
- **Never block on Maven.** Run every `mvn` command in the background and poll, reporting each check.
- **Maven invariants:** `-Dtest=` needs comma-separated class names (a `+` silently passes), never drop `-am`, and always pass `-Dsurefire.failIfNoSpecifiedTests=false`.
- **Four test classes fail for environment reasons on any branch** (they need live ES/Redis). If one of them fails, it is not your change.
- **Manifest shape is main's shape**: `ManifestEntry.pagination()` returning `Pagination(type, total, byteRanges)`. #2226 renames this to `pages: {total, pagination: {type, ranges}}`. Every read of that shape must live in `ArtifactReader` and the test fixtures, nowhere else, so the later rebase is a handful of lines.
- **Only `COMPLETE` is servable.** `ManifestEntryStatus.EMPTY` (`isServable() == false`) means 404, never 200.
- **`total` is trusted** (spec decision 10): the page count comes from the entry, never from counting files on disk.
- **Content types:** `page` → `text/plain;charset=UTF-8`; `structure` `md` → `text/markdown;charset=UTF-8`; `structure` `xhtml` → `application/xhtml+xml;charset=UTF-8`.
- **Commit style:** semantic prefix, scope `artifacts` (e.g. `feat(artifacts): ...`). No Claude attribution, no co-author trailers.
- **No `ponytail:` comments** in committed code.

## File Structure

| File | Responsibility |
|---|---|
| `datashare-api/.../text/artifact/ArtifactType.java` (modify) | add `PAGE("page")` |
| `datashare-index/.../text/indexing/elasticsearch/ArtifactPath.java` (modify) | payload directory + page + content file naming, keyed by type |
| `datashare-index/.../text/artifact/ArtifactReader.java` (create) | servable-entry lookup, page resolution for both schemes, format probing |
| `datashare-app/.../utils/DocumentSourceAccess.java` (create) | the download gate + source payload builder, lifted verbatim |
| `datashare-app/.../web/DocumentResource.java` (modify) | delegate to `DocumentSourceAccess`; deprecate `/documents/content/pages` |
| `datashare-app/.../web/ArtifactResource.java` (create) | the five routes, HTTP concerns only |
| `datashare-app/.../mode/LocalMode.java`, `ServerMode.java` (modify) | register the resource |

Tests: `ArtifactTypeTest`, `ArtifactPathTest`, `ArtifactReaderTest` (create), `ArtifactResourceTest` (create), `DocumentResourceTest` (one added assertion).

---

### Task 1: `ArtifactType.PAGE` and the `ArtifactPath` payload helpers

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/text/artifact/ArtifactType.java:12-14`
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ArtifactPath.java`
- Test: `datashare-api/src/test/java/org/icij/datashare/text/artifact/ArtifactTypeTest.java`
- Test: `datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/ArtifactPathTest.java`

**Interfaces:**
- Consumes: `ArtifactPath.dir(Path projectRoot, String digest)` (existing).
- Produces: `ArtifactType.PAGE`; `ArtifactPath.PAGES_DIR`, `ArtifactPath.STRUCTURE_DIR`, `ArtifactPath.payloadDir(Path docArtifactDir, ArtifactType type)`, `ArtifactPath.payloadPage(Path docArtifactDir, ArtifactType type, int page, String extension)`, `ArtifactPath.payloadContent(Path docArtifactDir, ArtifactType type, String extension)`.

Note for the reviewer: `STRUCTURE_DIR`, and a `structurePage` equivalent, are also added by #2226 in the parallel session. This is the expected textual conflict; whoever lands second collapses their variant onto `payloadPage`.

- [ ] **Step 1: Write the failing tests**

Append to `ArtifactTypeTest`:

```java
    @Test
    public void test_page_is_a_known_type() {
        assertThat(ArtifactType.PAGE.token()).isEqualTo("page");
        assertThat(ArtifactType.fromToken("PAGE")).isEqualTo(ArtifactType.PAGE);
        assertThat(ArtifactType.tokens()).contains("page");
    }
```

Append to `ArtifactPathTest`:

```java
    @Test
    public void test_payload_dir_is_named_per_type() {
        Path node = Path.of("/artifact/prj/6a/bb/" + DIGEST);
        assertThat(ArtifactPath.payloadDir(node, ArtifactType.PAGE).toString()).isEqualTo(node + "/pages");
        assertThat(ArtifactPath.payloadDir(node, ArtifactType.STRUCTURE).toString()).isEqualTo(node + "/structure");
    }

    @Test
    public void test_payload_page_is_unpadded_and_one_based() {
        Path node = Path.of("/artifact/prj/6a/bb/" + DIGEST);
        assertThat(ArtifactPath.payloadPage(node, ArtifactType.PAGE, 1, "txt").toString()).isEqualTo(node + "/pages/page-1.txt");
        assertThat(ArtifactPath.payloadPage(node, ArtifactType.STRUCTURE, 12, "xhtml").toString()).isEqualTo(node + "/structure/page-12.xhtml");
    }

    @Test
    public void test_payload_content_is_the_byte_ranges_file() {
        Path node = Path.of("/artifact/prj/6a/bb/" + DIGEST);
        assertThat(ArtifactPath.payloadContent(node, ArtifactType.PAGE, "txt").toString()).isEqualTo(node + "/pages/content.txt");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_raw_has_no_payload_dir() {
        ArtifactPath.payloadDir(Path.of("/artifact/prj/6a/bb/" + DIGEST), ArtifactType.RAW);
    }
```

Add `import org.icij.datashare.text.artifact.ArtifactType;` to `ArtifactPathTest`.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
mvn -pl datashare-api -am test -Dtest=ArtifactTypeTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl datashare-index -am test -Dtest=ArtifactPathTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation failure, `cannot find symbol: variable PAGE` / `method payloadDir`.

- [ ] **Step 3: Add the enum constant**

In `ArtifactType`, between `RAW` and `STRUCTURE` so the token order matches the convention's table:

```java
    RAW("raw"),
    PAGE("page"),
    STRUCTURE("structure");
```

- [ ] **Step 4: Add the path helpers**

In `ArtifactPath`, after `manifest(...)`:

```java
    /** Payload subdirectory for a paginated type, under a document's artifact dir. */
    public static Path payloadDir(Path docArtifactDir, ArtifactType type) {
        return docArtifactDir.resolve(payloadDirName(type));
    }

    /** One page file of a filesystem-paginated payload: page-%d.<extension>, 1-based. */
    public static Path payloadPage(Path docArtifactDir, ArtifactType type, int page, String extension) {
        return payloadDir(docArtifactDir, type).resolve(String.format("page-%d.%s", page, extension));
    }

    /** The single file a byte-ranges-paginated payload slices pages out of. */
    public static Path payloadContent(Path docArtifactDir, ArtifactType type, String extension) {
        return payloadDir(docArtifactDir, type).resolve("content." + extension);
    }

    private static String payloadDirName(ArtifactType type) {
        return switch (type) {
            case PAGE -> PAGES_DIR;
            case STRUCTURE -> STRUCTURE_DIR;
            // raw is a single file next to manifest.json (extract-lib owns that name), not a directory.
            case RAW -> throw new IllegalArgumentException("artifact type 'raw' has no payload directory");
        };
    }
```

And with the other constants at the top:

```java
    public static final String PAGES_DIR = "pages";
    public static final String STRUCTURE_DIR = "structure";
```

Add `import org.icij.datashare.text.artifact.ArtifactType;`.

- [ ] **Step 5: Run both test classes to verify they pass**

```bash
mvn -pl datashare-api -am test -Dtest=ArtifactTypeTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl datashare-index -am test -Dtest=ArtifactPathTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl datashare-app -am test -Dtest=ArtifactRegistryTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. `ArtifactRegistryTest` lives in `datashare-app` and is run because adding an enum constant touches the `--artifacts` selector's error messages.

- [ ] **Step 6: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/text/artifact/ArtifactType.java \
        datashare-api/src/test/java/org/icij/datashare/text/artifact/ArtifactTypeTest.java \
        datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ArtifactPath.java \
        datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/ArtifactPathTest.java
git commit -m "feat(artifacts): add the page type and type-keyed payload path helpers"
```

---

### Task 2: `ArtifactReader` — servable entries and filesystem pages

**Files:**
- Create: `datashare-index/src/main/java/org/icij/datashare/text/artifact/ArtifactReader.java`
- Test: `datashare-index/src/test/java/org/icij/datashare/text/artifact/ArtifactReaderTest.java`

**Interfaces:**
- Consumes: `ArtifactPath.payloadPage`, `ManifestRepository.get(Path, String)`, `ManifestEntry.isComplete()/total()/pagination()`, `ManifestEntryStatus.COMPLETE/EMPTY`, `Pagination.filesystem(int)`.
- Produces: `new ArtifactReader(ManifestRepository)`, `ManifestEntry servableEntry(Path docArtifactDir, ArtifactType type)`, `byte[] page(Path docArtifactDir, ArtifactType type, ManifestEntry entry, int page, String extension)`. Both `throws IOException`. `page` returns `null` for any unservable page; callers map `null` to 404.

- [ ] **Step 1: Write the failing test**

```java
package org.icij.datashare.text.artifact;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class ArtifactReaderTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();
    private final ManifestRepository manifests = new FilesystemManifestRepository();
    private final ArtifactReader reader = new ArtifactReader(manifests);

    private Path withFilesystemPages(int total, String... pages) throws Exception {
        Path node = dir.getRoot().toPath();
        Files.createDirectories(ArtifactPath.payloadDir(node, ArtifactType.PAGE));
        for (int page = 1; page <= pages.length; page++) {
            Files.writeString(ArtifactPath.payloadPage(node, ArtifactType.PAGE, page, "txt"), pages[page - 1]);
        }
        manifests.put(node, ArtifactType.PAGE.token(),
                ManifestEntry.paginated(Map.of(), Pagination.filesystem(total)).withStatus(ManifestEntryStatus.COMPLETE));
        return node;
    }

    @Test
    public void test_servable_entry_is_null_when_no_manifest() throws Exception {
        assertThat(reader.servableEntry(dir.getRoot().toPath(), ArtifactType.PAGE)).isNull();
    }

    @Test
    public void test_servable_entry_is_null_for_another_type() throws Exception {
        Path node = withFilesystemPages(1, "page one");
        assertThat(reader.servableEntry(node, ArtifactType.STRUCTURE)).isNull();
    }

    @Test
    public void test_servable_entry_is_null_when_empty_status() throws Exception {
        Path node = dir.getRoot().toPath();
        manifests.put(node, ArtifactType.PAGE.token(), ManifestEntry.empty(Map.of()));
        assertThat(reader.servableEntry(node, ArtifactType.PAGE)).isNull();
    }

    @Test
    public void test_servable_entry_is_null_when_status_absent() throws Exception {
        Path node = dir.getRoot().toPath();
        manifests.put(node, ArtifactType.PAGE.token(), ManifestEntry.paginated(Map.of(), Pagination.filesystem(2)));
        assertThat(reader.servableEntry(node, ArtifactType.PAGE)).isNull();
    }

    @Test
    public void test_servable_entry_carries_the_total() throws Exception {
        Path node = withFilesystemPages(2, "one", "two");
        assertThat(reader.servableEntry(node, ArtifactType.PAGE).total()).isEqualTo(2);
    }

    @Test
    public void test_page_reads_the_filesystem_page() throws Exception {
        Path node = withFilesystemPages(2, "page one", "page two");
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(new String(reader.page(node, ArtifactType.PAGE, entry, 2, "txt"), StandardCharsets.UTF_8)).isEqualTo("page two");
    }

    @Test
    public void test_page_is_null_out_of_range() throws Exception {
        Path node = withFilesystemPages(2, "one", "two");
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 0, "txt")).isNull();
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 3, "txt")).isNull();
    }

    @Test
    public void test_page_is_null_when_the_file_is_missing() throws Exception {
        // total says 3, only 2 files exist: the manifest is authoritative for the count, so page 3
        // must 404 rather than the count shrinking on every request.
        Path node = withFilesystemPages(3, "one", "two");
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(entry.total()).isEqualTo(3);
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 3, "txt")).isNull();
    }

    @Test
    public void test_page_is_null_when_extension_does_not_exist() throws Exception {
        Path node = withFilesystemPages(1, "one");
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 1, "md")).isNull();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -pl datashare-index -am test -Dtest=ArtifactReaderTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation failure, `cannot find symbol: class ArtifactReader`.

- [ ] **Step 3: Write the minimal implementation**

```java
package org.icij.datashare.text.artifact;

import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Read side of the artifact store, mirroring {@link ArtifactProducer}. Owns every rule that
 *  involves the manifest or the on-disk layout, so serving code stays HTTP-only: what is
 *  servable, where a page lives, and what a missing payload means. */
public class ArtifactReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactReader.class);
    private final ManifestRepository manifests;

    public ArtifactReader(ManifestRepository manifests) {
        this.manifests = manifests;
    }

    /** The type's entry when it exists and is servable, else null. Callers map null to 404 and
     *  never inspect status themselves, so "servable" has one definition. */
    public ManifestEntry servableEntry(Path docArtifactDir, ArtifactType type) throws IOException {
        ManifestEntry entry = manifests.get(docArtifactDir, type.token());
        return entry != null && entry.isComplete() ? entry : null;
    }

    /** One page's bytes, or null when the page is out of range or its payload is missing. */
    public byte[] page(Path docArtifactDir, ArtifactType type, ManifestEntry entry, int page, String extension) throws IOException {
        Integer total = entry.total();
        if (total == null || page < 1 || page > total) {
            return null;
        }
        Path file = ArtifactPath.payloadPage(docArtifactDir, type, page, extension);
        if (!Files.isRegularFile(file)) {
            // In range per the manifest but absent on disk: the two disagree, which is worth
            // seeing in the logs rather than reshaping the advertised page count silently.
            LOGGER.warn("manifest advertises {} page(s) for '{}' but {} is missing", total, type.token(), file);
            return null;
        }
        return Files.readAllBytes(file);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
mvn -pl datashare-index -am test -Dtest=ArtifactReaderTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add datashare-index/src/main/java/org/icij/datashare/text/artifact/ArtifactReader.java \
        datashare-index/src/test/java/org/icij/datashare/text/artifact/ArtifactReaderTest.java
git commit -m "feat(artifacts): read servable manifest entries and filesystem-paginated pages"
```

---

### Task 3: `ArtifactReader` — byte-range pages and format probing

**Files:**
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/artifact/ArtifactReader.java`
- Test: `datashare-index/src/test/java/org/icij/datashare/text/artifact/ArtifactReaderTest.java`

**Interfaces:**
- Consumes: `ArtifactPath.payloadContent`, `Pagination.byteRanges(int, List<long[]>)`.
- Produces: `List<String> formats(Path docArtifactDir, ArtifactType type, ManifestEntry entry, List<String> candidates)` (no checked exception); `page(...)` additionally serves the `byteRanges` scheme.

Why both schemes: `Pagination` already models both, #2227 has not committed to one, and datashare-python's docling producer writes byte ranges (spec decision 6).

- [ ] **Step 1: Write the failing test**

Append to `ArtifactReaderTest` (and add `import java.util.List;`):

```java
    private Path withByteRanges(String content, String extension, long[]... ranges) throws Exception {
        Path node = dir.getRoot().toPath();
        Files.createDirectories(ArtifactPath.payloadDir(node, ArtifactType.PAGE));
        Files.writeString(ArtifactPath.payloadContent(node, ArtifactType.PAGE, extension), content);
        manifests.put(node, ArtifactType.PAGE.token(),
                ManifestEntry.paginated(Map.of(), Pagination.byteRanges(ranges.length, List.of(ranges)))
                        .withStatus(ManifestEntryStatus.COMPLETE));
        return node;
    }

    @Test
    public void test_page_reads_a_byte_range_slice() throws Exception {
        Path node = withByteRanges("page onepage two", "txt", new long[]{0, 8}, new long[]{8, 16});
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(new String(reader.page(node, ArtifactType.PAGE, entry, 1, "txt"), StandardCharsets.UTF_8)).isEqualTo("page one");
        assertThat(new String(reader.page(node, ArtifactType.PAGE, entry, 2, "txt"), StandardCharsets.UTF_8)).isEqualTo("page two");
    }

    @Test
    public void test_page_is_null_when_a_range_runs_past_end_of_file() throws Exception {
        Path node = withByteRanges("short", "txt", new long[]{0, 5}, new long[]{5, 99});
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 2, "txt")).isNull();
    }

    @Test
    public void test_page_is_null_when_the_content_file_is_missing() throws Exception {
        Path node = withByteRanges("content", "txt", new long[]{0, 7});
        Files.delete(ArtifactPath.payloadContent(node, ArtifactType.PAGE, "txt"));
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 1, "txt")).isNull();
    }

    @Test
    public void test_formats_probes_page_one_under_filesystem_pagination() throws Exception {
        Path node = withFilesystemPages(1, "one");
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.formats(node, ArtifactType.PAGE, entry, List.of("txt", "md"))).containsExactly("txt");
    }

    @Test
    public void test_formats_probes_the_content_file_under_byte_ranges() throws Exception {
        Path node = withByteRanges("content", "txt", new long[]{0, 7});
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.formats(node, ArtifactType.PAGE, entry, List.of("md", "txt"))).containsExactly("txt");
    }
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -pl datashare-index -am test -Dtest=ArtifactReaderTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation failure on `reader.formats(...)`, then (once that compiles) the byte-range tests fail because `page` only resolves filesystem paths.

- [ ] **Step 3: Write the implementation**

Add to `ArtifactReader` (and the imports `java.io.RandomAccessFile`, `java.util.List`):

```java
    private static final String BYTE_RANGES = "byteRanges";
```

Replace the body of `page` after the range check with a scheme branch:

```java
        if (isByteRanges(entry)) {
            return slice(ArtifactPath.payloadContent(docArtifactDir, type, extension),
                    entry.pagination().byteRanges().get(page - 1), type, total);
        }
        Path file = ArtifactPath.payloadPage(docArtifactDir, type, page, extension);
        ...
```

And add:

```java
    /** Which extensions are actually on disk, in the candidate order given. Probes per scheme,
     *  because the two schemes keep an extension in different files. */
    public List<String> formats(Path docArtifactDir, ArtifactType type, ManifestEntry entry, List<String> candidates) {
        boolean byteRanges = isByteRanges(entry);
        return candidates.stream()
                .filter(extension -> Files.isRegularFile(byteRanges
                        ? ArtifactPath.payloadContent(docArtifactDir, type, extension)
                        : ArtifactPath.payloadPage(docArtifactDir, type, 1, extension)))
                .toList();
    }

    private boolean isByteRanges(ManifestEntry entry) {
        return entry.pagination() != null && BYTE_RANGES.equals(entry.pagination().type());
    }

    // Half-open [start, end). A range outside the file means manifest and payload disagree, which
    // is a 404 for that page rather than a truncated body.
    private byte[] slice(Path content, long[] range, ArtifactType type, int total) throws IOException {
        if (!Files.isRegularFile(content)) {
            LOGGER.warn("manifest advertises {} byte-range page(s) for '{}' but {} is missing", total, type.token(), content);
            return null;
        }
        long start = range[0];
        long end = range[1];
        if (start < 0 || end < start || end > Files.size(content)) {
            LOGGER.warn("byte range [{}, {}) for '{}' is outside {}", start, end, type.token(), content);
            return null;
        }
        byte[] slice = new byte[(int) (end - start)];
        try (RandomAccessFile file = new RandomAccessFile(content.toFile(), "r")) {
            file.seek(start);
            file.readFully(slice);   // handles the partial-read loop
        }
        return slice;
    }
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
mvn -pl datashare-index -am test -Dtest=ArtifactReaderTest,FilesystemManifestRepositoryTest,ArtifactPathTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS, 15 tests in `ArtifactReaderTest`.

- [ ] **Step 5: Commit**

```bash
git add datashare-index/src/main/java/org/icij/datashare/text/artifact/ArtifactReader.java \
        datashare-index/src/test/java/org/icij/datashare/text/artifact/ArtifactReaderTest.java
git commit -m "feat(artifacts): serve byte-range pages and probe on-disk formats"
```

---

### Task 4: lift the download gate into `DocumentSourceAccess`

**Files:**
- Create: `datashare-app/src/main/java/org/icij/datashare/utils/DocumentSourceAccess.java`
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/DocumentResource.java:69-77,107-151,539-559`
- Test: `datashare-app/src/test/java/org/icij/datashare/web/DocumentResourceTest.java` (unchanged — its passing is the verification)

**Interfaces:**
- Consumes: `Repository`, `Indexer`, `PropertiesProvider`, `DocumentVerifier`, `SourceExtractor`, `Project.isAllowed`, `PayloadFormatter.error`.
- Produces: `new DocumentSourceAccess(Repository, Indexer, PropertiesProvider)`; `Payload gated(String project, String id, String routing, Context context, Function<Document, Payload> whenAllowed)`; `Payload source(Document document, String project, boolean inline, boolean filterMetadata)`; `boolean isSourceAvailable(Document document, Project servingProject)`.

This task is behavior-preserving. `DocumentResource`'s constructor signature does **not** change: it instantiates `DocumentSourceAccess` itself, exactly as it already instantiates `DocumentVerifier` at `:76`, so no existing test is touched. `ArtifactResource` gets it injected in Task 5.

- [ ] **Step 1: Run the existing tests to record the green baseline**

```bash
mvn -pl datashare-app -am test -Dtest=DocumentResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. Note the test count — it must be identical at Step 4.

- [ ] **Step 2: Create the collaborator with the two methods moved verbatim**

```java
package org.icij.datashare.utils;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.payload.Payload;
import net.codestory.http.types.ContentTypes;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.FileExtension;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.extract.extractor.EmbeddedDocumentExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Function;

import static java.util.Optional.ofNullable;
import static net.codestory.http.constants.Headers.CONTENT_LENGTH;
import static net.codestory.http.errors.NotFoundException.notFoundIfNull;
import static org.icij.datashare.text.Project.isAllowed;
import static org.icij.datashare.text.Project.project;

/** The single decision for serving a document's source bytes: who may download, whether the
 *  document exists, whether its root is within the size limit, and how the payload is built.
 *  Shared by DocumentResource (/documents/src) and ArtifactResource (/artifacts/raw) so the
 *  download restriction cannot drift into a bypass on one of the two routes. */
@Singleton
public class DocumentSourceAccess {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Repository repository;
    private final Indexer indexer;
    private final PropertiesProvider propertiesProvider;
    private final DocumentVerifier documentVerifier;

    @Inject
    public DocumentSourceAccess(Repository repository, Indexer indexer, PropertiesProvider propertiesProvider) {
        this.repository = repository;
        this.indexer = indexer;
        this.propertiesProvider = propertiesProvider;
        this.documentVerifier = new DocumentVerifier(indexer, propertiesProvider);
    }

    public Payload gated(final String project, final String id, final String routing,
                         final Context context, final Function<Document, Payload> whenAllowed) {
        boolean isProjectGranted = ((DatashareUser) context.currentUser()).isGranted(project);
        boolean isDownloadAllowed = isAllowed(repository.getProject(project), context.request().clientAddress());
        if (!isProjectGranted || !isDownloadAllowed) {
            return PayloadFormatter.error("You are not allowed to download this document", HttpStatus.FORBIDDEN);
        }
        List<String> sourceExcludes = List.of("content", "content_translated");
        Document document = notFoundIfNull(indexer.get(project, id, routing == null ? id : routing, sourceExcludes));
        if (!documentVerifier.isRootDocumentSizeAllowed(document, project(project))) {
            return PayloadFormatter.error("The file or its parent is too large", HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        }
        return whenAllowed.apply(document);
    }

    public boolean isSourceAvailable(Document document, Project servingProject) {
        return documentVerifier.isSourceAvailable(document, servingProject);
    }

    public Payload source(Document doc, String index, boolean inline, boolean filterMetadata) {
        try {
            InputStream from = new SourceExtractor(propertiesProvider, filterMetadata).getSource(project(index), doc);
            String contentType = ofNullable(doc.getContentType()).orElse(ContentTypes.get(doc.getPath().toFile().getName()));
            // OCR-routed embedded images are stored with a synthetic "image/ocr-<fmt>" content type
            // (the "ocr-" prefix routes them through the OCR parser). Serve the real media type so the
            // Content-Type header and the download filename extension are correct (otherwise ".bin").
            if (contentType != null && contentType.startsWith("image/ocr-")) {
                contentType = "image/" + contentType.substring("image/ocr-".length());
            }
            Payload payload = new Payload(contentType, from);
            if (!filterMetadata && doc.getContentLength() > 0) {
                payload.withHeader(CONTENT_LENGTH, String.valueOf(doc.getContentLength()));
            }
            String fileName = doc.isRootDocument() ? doc.getName() : doc.getId().substring(0, 10) + "." + FileExtension.get(contentType);
            return inline ? payload : payload.withHeader("Content-Disposition", "attachment;filename=\"" + fileName + "\"");
        } catch (FileNotFoundException | EmbeddedDocumentExtractor.ContentNotFoundException fnf) {
            logger.error("unable to read document source file", fnf);
            return Payload.notFound();
        }
    }
}
```

- [ ] **Step 3: Delegate from `DocumentResource`**

Replace the `documentVerifier` field with `private final DocumentSourceAccess sources;`, initialised in the constructor as `this.sources = new DocumentSourceAccess(repository, indexer, propertiesProvider);`. Then:

- `getSourceFile` body → `return sources.gated(project, id, routing, context, document -> sources.source(document, project, inline, parseBoolean(filterMetadata)));`
- `headSourceFile` body → `return sources.gated(project, id, routing, context, document -> sources.isSourceAvailable(document, project(project)) ? ok() : Payload.notFound());`
- Delete the private `sourceFile(...)` method (`:138-151`) and the private `getPayload(...)` method (`:539-559`), keeping the route-precedence comment above `headSourceFile` (`:126-128`) where it is.
- Drop the imports that are now unused (`SourceExtractor`, `FileExtension`, `EmbeddedDocumentExtractor`, `ContentTypes`, `CONTENT_LENGTH`, `HttpStatus`, `DocumentVerifier`, `PayloadFormatter`, `Function`, `Project.isAllowed`) **only if** nothing else in the file uses them — check each with a search before removing, several are used by other routes.

- [ ] **Step 4: Run the existing tests to verify nothing changed**

```bash
mvn -pl datashare-app -am test -Dtest=DocumentResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS with the same test count as Step 1, including `test_get_source_file_forbidden_index` (403), `test_get_source_root_too_big` (413), `test_source_file_not_found_should_return_404` (which asserts the log line "unable to read document source file" still appears — it now comes from the new class's logger, and the assertion matches on message text, not logger name).

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/utils/DocumentSourceAccess.java \
        datashare-app/src/main/java/org/icij/datashare/web/DocumentResource.java
git commit -m "refactor(documents): extract the source download gate into DocumentSourceAccess"
```

---

### Task 5: `ArtifactResource` with the `page` routes

**Files:**
- Create: `datashare-app/src/main/java/org/icij/datashare/web/ArtifactResource.java`
- Modify: `datashare-app/src/main/java/org/icij/datashare/mode/LocalMode.java:38`, `datashare-app/src/main/java/org/icij/datashare/mode/ServerMode.java:131`
- Test: `datashare-app/src/test/java/org/icij/datashare/web/ArtifactResourceTest.java`

**Interfaces:**
- Consumes: `ArtifactReader`, `FilesystemManifestRepository`, `ArtifactPath.dir`, `DocumentSourceAccess`, `ARTIFACT_DIR_OPT`, `requireGranted`, `PayloadFormatter.json`.
- Produces: routes `GET /:project/artifacts/page/:id` and `GET /:project/artifacts/page/:id/:page`; the private helpers `docArtifactDir(...)`, `manifest(...)`, `payload(...)`, `parsePageNumber(...)` that Tasks 6 and 7 reuse.

- [ ] **Step 1: Write the failing test**

```java
package org.icij.datashare.web;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.tasks.MockIndexer;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.utils.DocumentSourceAccess;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACT_DIR_OPT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class ArtifactResourceTest extends AbstractProdWebServerTest {
    // 64-char digests: ArtifactPath.dir shards on the first two hex pairs.
    private static final String DIGEST = "6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e";
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Mock JooqRepository jooqRepository;
    @Mock Indexer indexer;
    @Mock PropertiesProvider propertiesProvider;
    MockIndexer mockIndexer;
    File artifactDir;

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        mockIndexer = new MockIndexer(indexer);
        artifactDir = temp.newFolder("artifacts");
        when(propertiesProvider.get(ARTIFACT_DIR_OPT)).thenReturn(Optional.of(artifactDir.toString()));
        when(propertiesProvider.getProperties()).thenReturn(new Properties());
        when(propertiesProvider.createMerged(any())).thenCallRealMethod();
        configure(routes -> routes
                .add(new ArtifactResource(indexer, propertiesProvider,
                        new DocumentSourceAccess(jooqRepository, indexer, propertiesProvider)))
                .filter(new LocalUserFilter(new PropertiesProvider(), jooqRepository)));
    }

    // Indexes the document and returns its content-addressed artifact dir.
    private Path indexedDocDir(String digest) throws Exception {
        File file = new File(temp.getRoot(), digest + ".txt");
        MockIndexer.write(file, "content");
        mockIndexer.indexFile("local-datashare", digest, file.toPath(), "text/plain", null);
        Path dir = artifactDir.toPath().resolve("local-datashare")
                .resolve(digest.substring(0, 2)).resolve(digest.substring(2, 4)).resolve(digest);
        Files.createDirectories(dir);
        return dir;
    }

    private void writeManifest(Path docDir, String json) throws Exception {
        Files.writeString(docDir.resolve("manifest.json"), json);
    }

    private void writePages(Path docDir, String extension, String... pages) throws Exception {
        Path pagesDir = docDir.resolve("pages");
        Files.createDirectories(pagesDir);
        for (int page = 1; page <= pages.length; page++) {
            Files.writeString(pagesDir.resolve(String.format("page-%d.%s", page, extension)), pages[page - 1]);
        }
    }

    private static String filesystemManifest(String type, int total) {
        return "{\"" + type + "\": {\"status\": \"complete\", \"taskInput\": {\"type\": \"" + type + "\", \"version\": 1},"
                + " \"pagination\": {\"type\": \"filesystem\", \"total\": " + total + "}}}";
    }

    @Test
    public void test_page_manifest_forbidden_for_non_member_project() {
        get("/api/foo_index/artifacts/page/" + DIGEST).should().respond(403);
    }

    @Test
    public void test_page_manifest_not_found_for_unknown_document() {
        get("/api/local-datashare/artifacts/page/" + DIGEST).should().respond(404);
    }

    @Test
    public void test_page_manifest_not_found_when_artifact_dir_unset() throws Exception {
        indexedDocDir(DIGEST);
        when(propertiesProvider.get(ARTIFACT_DIR_OPT)).thenReturn(Optional.empty());
        get("/api/local-datashare/artifacts/page/" + DIGEST).should().respond(404);
    }

    @Test
    public void test_page_manifest_not_found_without_manifest() throws Exception {
        indexedDocDir(DIGEST);
        get("/api/local-datashare/artifacts/page/" + DIGEST).should().respond(404);
    }

    @Test
    public void test_page_manifest_not_found_when_type_absent_from_manifest() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writeManifest(docDir, filesystemManifest("structure", 2));
        get("/api/local-datashare/artifacts/page/" + DIGEST).should().respond(404);
    }

    @Test
    public void test_page_manifest_not_found_when_entry_is_empty() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writeManifest(docDir, "{\"page\": {\"status\": \"empty\", \"taskInput\": {}}}");
        get("/api/local-datashare/artifacts/page/" + DIGEST).should().respond(404);
    }

    @Test
    public void test_page_manifest_returns_the_page_count() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, "txt", "page one", "page two");
        writeManifest(docDir, filesystemManifest("page", 2));
        get("/api/local-datashare/artifacts/page/" + DIGEST).should()
                .respond(200).haveType("application/json").contain("\"pages\":2");
    }

    @Test
    public void test_page_serves_one_page_as_plain_text() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, "txt", "page one", "page two");
        writeManifest(docDir, filesystemManifest("page", 2));
        get("/api/local-datashare/artifacts/page/" + DIGEST + "/2").should()
                .respond(200).haveType("text/plain;charset=UTF-8").contain("page two");
    }

    @Test
    public void test_page_out_of_range_and_non_numeric_are_not_found() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, "txt", "page one");
        writeManifest(docDir, filesystemManifest("page", 1));
        get("/api/local-datashare/artifacts/page/" + DIGEST + "/0").should().respond(404);
        get("/api/local-datashare/artifacts/page/" + DIGEST + "/2").should().respond(404);
        get("/api/local-datashare/artifacts/page/" + DIGEST + "/abc").should().respond(404);
    }

    @Test
    public void test_page_missing_on_disk_is_not_found_but_the_count_still_reports_it() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, "txt", "page one", "page two");
        writeManifest(docDir, filesystemManifest("page", 3));
        get("/api/local-datashare/artifacts/page/" + DIGEST).should().contain("\"pages\":3");
        get("/api/local-datashare/artifacts/page/" + DIGEST + "/3").should().respond(404);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -pl datashare-app -am test -Dtest=ArtifactResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation failure, `cannot find symbol: class ArtifactResource`.

- [ ] **Step 3: Write the resource**

```java
package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCliOptions;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.artifact.ArtifactReader;
import org.icij.datashare.text.artifact.ArtifactType;
import org.icij.datashare.text.artifact.FilesystemManifestRepository;
import org.icij.datashare.text.artifact.ManifestEntry;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.utils.DocumentSourceAccess;
import org.icij.datashare.utils.PayloadFormatter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.web.errors.ForbiddenException.requireGranted;

/** Serves per-document artifacts from artifactDir, driven by each document's manifest.json.
 *  Derived types (page, structure) are strict-store: only a COMPLETE entry is served, anything
 *  else is a 404. `raw` goes through the same gate as /documents/src, because a root document's
 *  raw entry is EMPTY by design and strict gating would 404 every root document. */
@Singleton
@Prefix("/api")
public class ArtifactResource {
    private final Indexer indexer;
    private final PropertiesProvider propertiesProvider;
    private final DocumentSourceAccess sources;
    private final ArtifactReader reader = new ArtifactReader(new FilesystemManifestRepository());

    @Inject
    public ArtifactResource(Indexer indexer, PropertiesProvider propertiesProvider, DocumentSourceAccess sources) {
        this.indexer = indexer;
        this.propertiesProvider = propertiesProvider;
        this.sources = sources;
    }

    @Operation(description = "Fetches the number of persisted plain-text pages for a document.",
            parameters = {
                    @Parameter(name = "project", description = "the project id", in = ParameterIn.PATH),
                    @Parameter(name = "id", description = "the document id", in = ParameterIn.PATH),
                    @Parameter(name = "routing", description = "routing key if not a root document", in = ParameterIn.QUERY)
            }
    )
    @ApiResponse(responseCode = "200", description = "JSON {\"pages\": N}")
    @ApiResponse(responseCode = "403", description = "forbidden if the user doesn't have access to the project")
    @ApiResponse(responseCode = "404", description = "if the document, artifactDir, or a complete page artifact is not found")
    @Get("/:project/artifacts/page/:id?routing=:routing")
    public Payload pageManifest(final String project, final String id, final String routing, final Context context) throws IOException {
        return manifest(project, id, routing, context, ArtifactType.PAGE, List.of());
    }

    @Operation(description = "Fetches one persisted plain-text page of a document.",
            parameters = {
                    @Parameter(name = "project", description = "the project id", in = ParameterIn.PATH),
                    @Parameter(name = "id", description = "the document id", in = ParameterIn.PATH),
                    @Parameter(name = "page", description = "1-based page number", in = ParameterIn.PATH),
                    @Parameter(name = "routing", description = "routing key if not a root document", in = ParameterIn.QUERY)
            }
    )
    @ApiResponse(responseCode = "200", description = "the page as text/plain")
    @ApiResponse(responseCode = "403", description = "forbidden if the user doesn't have access to the project")
    @ApiResponse(responseCode = "404", description = "if the document, the artifact, or the page is not found")
    @Get("/:project/artifacts/page/:id/:page?routing=:routing")
    public Payload pageContent(final String project, final String id, final String page,
                               final String routing, final Context context) throws IOException {
        return payload(project, id, page, routing, context, ArtifactType.PAGE, "txt", "text/plain;charset=UTF-8");
    }

    // Resolves the content-addressed dir of a granted, existing document. Returns null when the
    // document is unknown or artifactDir is unset (both 404); throws ForbiddenException (403) when
    // the project is not granted. The document is always resolved through the indexer first, so a
    // URL cannot probe arbitrary digests or another project's data.
    private Path docArtifactDir(final String project, final String id, final String routing, final Context context) {
        requireGranted(context, project);
        Document document = indexer.get(project, id, ofNullable(routing).orElse(id), List.of("content", "content_translated"));
        if (document == null) {
            return null;
        }
        return propertiesProvider.get(DatashareCliOptions.ARTIFACT_DIR_OPT)
                .map(dir -> ArtifactPath.dir(Path.of(dir).resolve(project), document.getId()))
                .orElse(null);
    }

    private Payload manifest(final String project, final String id, final String routing, final Context context,
                             final ArtifactType type, final List<String> formats) throws IOException {
        Path docArtifactDir = docArtifactDir(project, id, routing, context);
        if (docArtifactDir == null) {
            return Payload.notFound();
        }
        ManifestEntry entry = reader.servableEntry(docArtifactDir, type);
        if (entry == null || entry.total() == null) {
            return Payload.notFound();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pages", entry.total());
        if (!formats.isEmpty()) {
            body.put("formats", reader.formats(docArtifactDir, type, entry, formats));
        }
        return PayloadFormatter.json(body).withCode(200);
    }

    private Payload payload(final String project, final String id, final String page, final String routing,
                            final Context context, final ArtifactType type, final String extension,
                            final String contentType) throws IOException {
        Path docArtifactDir = docArtifactDir(project, id, routing, context);
        if (docArtifactDir == null) {
            return Payload.notFound();
        }
        ManifestEntry entry = reader.servableEntry(docArtifactDir, type);
        if (entry == null) {
            return Payload.notFound();
        }
        int pageNumber = parsePageNumber(page);
        byte[] bytes = pageNumber < 1 ? null : reader.page(docArtifactDir, type, entry, pageNumber, extension);
        return bytes == null ? Payload.notFound() : new Payload(contentType, bytes).withCode(200);
    }

    // -1 for anything that is not a positive integer, so non-numeric and out-of-range share the
    // same 404 answer.
    private static int parsePageNumber(String page) {
        try {
            return Integer.parseInt(page);
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }
}
```

- [ ] **Step 4: Register the resource in both modes**

In `LocalMode.java`, in the route list after `add(DocumentResource.class).`, add `add(ArtifactResource.class).`; do the same in `ServerMode.java`. `OpenApiResource` scans the `org.icij.datashare.web` package by classloader (`OpenApiResource.java:41`), so no OpenAPI wiring is needed.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
mvn -pl datashare-app -am test -Dtest=ArtifactResourceTest,DocumentResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS, 10 tests in `ArtifactResourceTest`, `DocumentResourceTest` unchanged.

- [ ] **Step 6: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/web/ArtifactResource.java \
        datashare-app/src/test/java/org/icij/datashare/web/ArtifactResourceTest.java \
        datashare-app/src/main/java/org/icij/datashare/mode/LocalMode.java \
        datashare-app/src/main/java/org/icij/datashare/mode/ServerMode.java
git commit -m "feat(artifacts): serve persisted pages from a dedicated ArtifactResource"
```

---

### Task 6: the `structure` routes with `?format=`

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/ArtifactResource.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/web/ArtifactResourceTest.java`

**Interfaces:**
- Consumes: `manifest(...)`, `payload(...)` from Task 5; `ArtifactReader.formats`.
- Produces: routes `GET /:project/artifacts/structure/:id` and `GET /:project/artifacts/structure/:id/:page?format=`.

Serving XHTML is safe because #2226 sanitizes at produce time (its decision 7). The CSP and nosniff headers are this side's defense in depth; the residual risk for non-conformant docling-written payloads is recorded in the spec, decision 7.

- [ ] **Step 1: Write the failing test**

Append to `ArtifactResourceTest`:

```java
    private void writeStructurePages(Path docDir, String extension, String... pages) throws Exception {
        Path structureDir = docDir.resolve("structure");
        Files.createDirectories(structureDir);
        for (int page = 1; page <= pages.length; page++) {
            Files.writeString(structureDir.resolve(String.format("page-%d.%s", page, extension)), pages[page - 1]);
        }
    }

    @Test
    public void test_structure_manifest_lists_the_formats_on_disk() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writeStructurePages(docDir, "md", "# one", "# two");
        writeStructurePages(docDir, "xhtml", "<html><body>one</body></html>", "<html><body>two</body></html>");
        writeManifest(docDir, filesystemManifest("structure", 2));
        get("/api/local-datashare/artifacts/structure/" + DIGEST).should()
                .respond(200).contain("\"pages\":2").contain("\"md\"").contain("\"xhtml\"");
    }

    @Test
    public void test_structure_manifest_omits_a_format_absent_from_disk() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writeStructurePages(docDir, "md", "# one");
        writeManifest(docDir, filesystemManifest("structure", 1));
        get("/api/local-datashare/artifacts/structure/" + DIGEST).should()
                .respond(200).contain("\"formats\":[\"md\"]");
    }

    @Test
    public void test_structure_page_defaults_to_markdown() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writeStructurePages(docDir, "md", "# one", "# two");
        writeManifest(docDir, filesystemManifest("structure", 2));
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/2").should()
                .respond(200).haveType("text/markdown;charset=UTF-8").contain("# two");
    }

    @Test
    public void test_structure_page_serves_xhtml_with_hardening_headers() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writeStructurePages(docDir, "xhtml", "<html><body>one</body></html>");
        writeManifest(docDir, filesystemManifest("structure", 1));
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/1?format=xhtml").should()
                .respond(200)
                .haveType("application/xhtml+xml;charset=UTF-8")
                .haveHeader("Content-Security-Policy", "default-src 'none'; sandbox")
                .haveHeader("X-Content-Type-Options", "nosniff");
    }

    @Test
    public void test_structure_page_rejects_an_unsupported_format() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writeStructurePages(docDir, "md", "# one");
        writeManifest(docDir, filesystemManifest("structure", 1));
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/1?format=pdf").should()
                .respond(400).contain("md").contain("xhtml");
    }

    @Test
    public void test_structure_page_not_found_for_a_format_absent_on_disk() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writeStructurePages(docDir, "md", "# one");
        writeManifest(docDir, filesystemManifest("structure", 1));
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/1?format=xhtml").should().respond(404);
    }

    @Test
    public void test_structure_forbidden_for_non_member_project() {
        get("/api/foo_index/artifacts/structure/" + DIGEST).should().respond(403);
        get("/api/foo_index/artifacts/structure/" + DIGEST + "/1").should().respond(403);
    }
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -pl datashare-app -am test -Dtest=ArtifactResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL — the structure routes return 404 (no route matches) instead of 200/400.

- [ ] **Step 3: Add the routes**

Add the format table and the two routes:

```java
    // Extensions are fixed by type (convention §71). The map is the ?format= whitelist and content-type
    // lookup; the list is the order of record for disk probing and for the error message, because
    // Map.of has no defined iteration order.
    private static final Map<String, String> STRUCTURE_CONTENT_TYPES = Map.of(
            "md", "text/markdown;charset=UTF-8",
            "xhtml", "application/xhtml+xml;charset=UTF-8");
    private static final List<String> STRUCTURE_FORMATS = List.of("md", "xhtml");
```

```java
    @Operation(description = "Fetches the number of structure pages for a document and the formats available on disk.",
            parameters = {
                    @Parameter(name = "project", description = "the project id", in = ParameterIn.PATH),
                    @Parameter(name = "id", description = "the document id", in = ParameterIn.PATH),
                    @Parameter(name = "routing", description = "routing key if not a root document", in = ParameterIn.QUERY)
            }
    )
    @ApiResponse(responseCode = "200", description = "JSON {\"pages\": N, \"formats\": [\"md\", \"xhtml\"]}")
    @ApiResponse(responseCode = "403", description = "forbidden if the user doesn't have access to the project")
    @ApiResponse(responseCode = "404", description = "if the document, artifactDir, or a complete structure artifact is not found")
    @Get("/:project/artifacts/structure/:id?routing=:routing")
    public Payload structureManifest(final String project, final String id, final String routing, final Context context) throws IOException {
        return manifest(project, id, routing, context, ArtifactType.STRUCTURE, STRUCTURE_FORMATS);
    }

    @Operation(description = "Fetches one structure page of a document, as Markdown (default) or XHTML.",
            parameters = {
                    @Parameter(name = "project", description = "the project id", in = ParameterIn.PATH),
                    @Parameter(name = "id", description = "the document id", in = ParameterIn.PATH),
                    @Parameter(name = "page", description = "1-based page number", in = ParameterIn.PATH),
                    @Parameter(name = "routing", description = "routing key if not a root document", in = ParameterIn.QUERY),
                    @Parameter(name = "format", description = "md (default) or xhtml", in = ParameterIn.QUERY)
            }
    )
    @ApiResponse(responseCode = "200", description = "the page as text/markdown or application/xhtml+xml")
    @ApiResponse(responseCode = "400", description = "if format is not one of md, xhtml")
    @ApiResponse(responseCode = "403", description = "forbidden if the user doesn't have access to the project")
    @ApiResponse(responseCode = "404", description = "if the document, the artifact, the page, or that format is not found")
    @Get("/:project/artifacts/structure/:id/:page?routing=:routing&format=:format")
    public Payload structurePage(final String project, final String id, final String page, final String routing,
                                 final String format, final Context context) throws IOException {
        String extension = ofNullable(format).filter(value -> !value.isBlank()).orElse("md");
        String contentType = STRUCTURE_CONTENT_TYPES.get(extension);
        if (contentType == null) {
            // A bad format is a request error: 404 on this route means "no such page".
            return PayloadFormatter.error("unsupported format '" + extension + "'; supported formats: "
                    + String.join(", ", STRUCTURE_FORMATS), HttpStatus.BAD_REQUEST);
        }
        Payload payload = payload(project, id, page, routing, context, ArtifactType.STRUCTURE, extension, contentType);
        // The on-disk XHTML is written pre-sanitized by the structure producer; these headers are
        // the serving side's defense in depth for a payload written by another producer.
        return "xhtml".equals(extension)
                ? payload.withHeader("Content-Security-Policy", "default-src 'none'; sandbox")
                        .withHeader("X-Content-Type-Options", "nosniff")
                : payload;
    }
```

Add `import net.codestory.http.constants.HttpStatus;`.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
mvn -pl datashare-app -am test -Dtest=ArtifactResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS, 17 tests.

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/web/ArtifactResource.java \
        datashare-app/src/test/java/org/icij/datashare/web/ArtifactResourceTest.java
git commit -m "feat(artifacts): serve structure pages as markdown or xhtml"
```

---

### Task 7: the `raw` route

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/ArtifactResource.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/web/ArtifactResourceTest.java`

**Interfaces:**
- Consumes: `DocumentSourceAccess.gated`, `DocumentSourceAccess.source` from Task 4.
- Produces: route `GET /:project/artifacts/raw/:id?routing=&inline=`.

- [ ] **Step 1: Write the failing test**

Append to `ArtifactResourceTest` (add imports `org.icij.datashare.text.Document`, `org.icij.datashare.text.DocumentBuilder`, `org.icij.datashare.text.Project`, and `static org.icij.datashare.cli.DatashareCliOptions.EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT`, plus `when(propertiesProvider.get(EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT)).thenReturn(Optional.of("1G"));` in `setUp`):

```java
    @Test
    public void test_raw_serves_the_source_bytes_as_an_attachment() throws Exception {
        File file = new File(temp.getRoot(), "raw-source.txt");
        MockIndexer.write(file, "source bytes");
        mockIndexer.indexFile("local-datashare", DIGEST, file.toPath(), "text/plain", null);
        get("/api/local-datashare/artifacts/raw/" + DIGEST).should()
                .respond(200).contain("source bytes")
                .haveHeader("Content-Disposition", "attachment;filename=\"raw-source.txt\"");
    }

    @Test
    public void test_raw_serves_inline_when_asked() throws Exception {
        File file = new File(temp.getRoot(), "raw-inline.txt");
        MockIndexer.write(file, "source bytes");
        mockIndexer.indexFile("local-datashare", DIGEST, file.toPath(), "text/plain", null);
        get("/api/local-datashare/artifacts/raw/" + DIGEST + "?inline=true").should()
                .respond(200).should().not().haveHeader("Content-Disposition", "attachment;filename=\"raw-inline.txt\"");
    }

    @Test
    public void test_raw_forbidden_for_non_member_project() {
        get("/api/foo_index/artifacts/raw/" + DIGEST).should().respond(403);
    }

    @Test
    public void test_raw_not_found_for_unknown_document() {
        get("/api/local-datashare/artifacts/raw/" + DIGEST).should().respond(404);
    }

    @Test
    public void test_raw_too_large_root_document_is_rejected() {
        // Same rule as /documents/src: the gate is shared, so this must answer 413 here too.
        Project index = new Project("local-datashare");
        Document root = DocumentBuilder.createDoc("bar").with(index).withContentLength(2L * 1024 * 1024 * 1024).build();
        Document embedded = DocumentBuilder.createDoc(DIGEST).with(index).with(temp.getRoot().toPath().resolve("any.txt"))
                .withParentId("bar").withRootId("bar").build();
        mockIndexer.indexFile("local-datashare", root, embedded);
        get("/api/local-datashare/artifacts/raw/" + DIGEST + "?routing=bar").should().respond(413);
    }
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -pl datashare-app -am test -Dtest=ArtifactResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL — 404 (no such route) instead of 200/413.

- [ ] **Step 3: Add the route**

```java
    @Operation(description = "Fetches a document's raw (embedded or source) bytes. Same access rules as /documents/src: project membership, the project's download restriction, and the root-document size limit.",
            parameters = {
                    @Parameter(name = "project", description = "the project id", in = ParameterIn.PATH),
                    @Parameter(name = "id", description = "the document id", in = ParameterIn.PATH),
                    @Parameter(name = "routing", description = "routing key if not a root document", in = ParameterIn.QUERY),
                    @Parameter(name = "inline", description = "if true, serve without the attachment disposition", in = ParameterIn.QUERY)
            }
    )
    @ApiResponse(responseCode = "200", description = "the raw bytes, with the document's content type")
    @ApiResponse(responseCode = "403", description = "forbidden if the user doesn't have access to the project or downloads are restricted")
    @ApiResponse(responseCode = "404", description = "if no document is found or its bytes cannot be read")
    @ApiResponse(responseCode = "413", description = "if the root document is too large and no raw artifact is cached for this embedded document")
    @Get("/:project/artifacts/raw/:id?routing=:routing&inline=:inline")
    public Payload raw(final String project, final String id, final String routing,
                       final String inline, final Context context) {
        boolean serveInline = parseBoolean(inline);
        return sources.gated(project, id, routing, context,
                document -> sources.source(document, project, serveInline, false));
    }
```

Add `import static java.lang.Boolean.parseBoolean;`.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
mvn -pl datashare-app -am test -Dtest=ArtifactResourceTest,DocumentResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS, 22 tests in `ArtifactResourceTest`.

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/web/ArtifactResource.java \
        datashare-app/src/test/java/org/icij/datashare/web/ArtifactResourceTest.java
git commit -m "feat(artifacts): serve raw bytes through the shared download gate"
```

---

### Task 8: deprecate `/documents/content/pages`

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/DocumentResource.java:216-224` (annotations only)
- Test: none added; `DocumentResourceTest` must stay green untouched

**Interfaces:**
- Consumes: nothing new.
- Produces: nothing. Contract metadata only: the implementation, signature, and response stay exactly as they are (spec decision 5), so no consumer loses a working feature.

This task has **no test cycle**, deliberately: the change is two annotations and a description string. There is no runtime behavior to assert, and a test that asserts an annotation exists tests the compiler. A `Deprecation: true` response header was considered and cut — it would force the return type from `List<String>` to `Payload`, degrade the OpenAPI response schema, and need a test, all to signal deprecation at runtime on a route with zero consumers in any ICIJ repo. `deprecated = true` in the contract does the job.

- [ ] **Step 1: Add the deprecation metadata**

On `getContentByPage` (`DocumentResource.java:216-240`), add `deprecated = true` to the `@Operation`, point the description at the replacement, and add `@Deprecated` to the method. Change nothing else — not the signature, not the body:

```java
    @Operation(deprecated = true,
            description = "Deprecated: use GET /:project/artifacts/page/:id/:page, which serves the persisted page artifact instead of reparsing the source on every request. This route still reparses live and is kept until clients migrate.",
            parameters = {
                    @Parameter(name = "project", description = "the project id", in = ParameterIn.PATH),
                    @Parameter(name = "id", description = "the document id", in = ParameterIn.PATH),
                    @Parameter(name = "routing", description = "routing key if not a root document", in = ParameterIn.QUERY)
            }
    )
    @ApiResponse(responseCode = "200", description = "JSON containing text pages array", useReturnTypeSchema = true)
    @Deprecated
    @Get("/:project/documents/content/pages/:id?routing=:routing")
    public List<String> getContentByPage(final String project, final String id, final String routing, final Context context) throws IOException {
```

- [ ] **Step 2: Run the tests to verify nothing changed**

```bash
mvn -pl datashare-app -am test -Dtest=DocumentResourceTest,ArtifactResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS, same counts as before. `test_get_pages_of_document_with_OCR`, `..._without_OCR`, and `test_get_content_by_page_forbidden_for_non_member_project` must be untouched and green — if any of them changes, you altered behavior and this task did not.

- [ ] **Step 3: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/web/DocumentResource.java
git commit -m "docs(documents): deprecate content/pages in favour of the page artifact route"
```

---

### Task 9: full-module verification and PR

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite for the three touched modules in the background**

```bash
mvn -pl datashare-api,datashare-index,datashare-app -am test
```

Poll and report. Separate pre-existing environment failures (the four classes that need live ES/Redis) from anything your change introduced, and say which is which.

- [ ] **Step 2: Review your own diff as a hostile reviewer**

`git diff main...HEAD`. For each hunk, state what input is now handled differently. Specifically confirm: no route answers 200 for an `EMPTY` entry; `/documents/src` behavior is byte-identical; no security check exists in two places; and `ArtifactReader` is the only file that reads `ManifestEntry.pagination()`.

- [ ] **Step 3: Push and open the PR**

```bash
git push -u origin feat/artifacts-resource
```

PR body: one-line summary, then the changes as tagged bullets. Reference `#2228` and note the three coordination points with #2226/#2227 (the `ArtifactPath` helpers, `ArtifactType.PAGE`, and the manifest reshape that will require a rebase of `ArtifactReader` plus the test fixtures).

---

## Self-Review

**Spec coverage:** raw/page/structure routes → Tasks 5-7; strict-store gating and the 403/404/400/413 matrix → Tasks 5-7 tests; both pagination schemes → Task 3; `formats` probing → Tasks 3, 6; shared gate → Task 4; `/documents/content/pages` deprecation → Task 8; `ArtifactType.PAGE` and path helpers → Task 1; decision 10 (trusted `total`) → Task 2 test `test_page_is_null_when_the_file_is_missing` and Task 5 test `test_page_missing_on_disk_is_not_found_but_the_count_still_reports_it`; xhtml hardening headers → Task 6. Registration in both modes → Task 5, Step 4. Convention-doc update is listed as a spec follow-up, not a task, deliberately: it lives in another repo.

**Naming consistency:** `payloadDir` / `payloadPage` / `payloadContent` (Task 1) are used under those names in Tasks 2, 3, and via `ArtifactReader` only. `servableEntry`, `page`, `formats` (Tasks 2-3) are called with those exact signatures in Tasks 5-6. `gated`, `source`, `isSourceAvailable` (Task 4) are called in Tasks 4 and 7. `docArtifactDir`, `manifest`, `payload`, `parsePageNumber` (Task 5) are reused in Task 6.

**Ponytail pass applied:** the `Deprecation` response header (and the return-type change it required) is cut from Task 8; `slice` uses `RandomAccessFile.readFully` instead of a hand-rolled `ByteBuffer` partial-read loop; three single-use constants and one single-call-site helper are inlined. About 22 lines lighter than the first draft.
