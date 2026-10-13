# Structure artifact (#2226) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a per-page structured rendering (`page-N.xhtml` + `page-N.md`) for every document under `artifactDir`, recorded in the document's `manifest.json`, from the ARTIFACT stage.

**Architecture:** A `StructureArtifact implements Artifact` writes payload files and returns a manifest entry; the existing `ArtifactProducer` loop (skip-if-current, produce, stamp `COMPLETE`, write under the per-document lock, isolate failures) is reused untouched. The Tika pipeline itself is carried over from the PoC branch `poc/tika-document-structure-extraction` as a standalone `StructureMarkdownExtractor` returning per-page (XHTML, Markdown) pairs. The manifest records in datashare-api are reshaped to the storage convention (`pages` + nested `pagination`).

**Tech Stack:** Java 21, Maven multi-module, Tika 3.3.0 (`AutoDetectParser`, `ToXMLContentHandler`, `PDFParserConfig`, `IdentityHtmlMapper`), jsoup 1.22.1 (`Cleaner`/`Safelist`), flexmark-html2md-converter 0.64.8, JUnit 4 + fest-assert 1.4 + Mockito.

**Spec:** `docs/superpowers/specs/2026-07-31-structure-artifact-design.md`.

## Global Constraints

- Produce-only. No serving, no endpoint, no `ArtifactResource` (that is #2228). Do not touch `DocumentResource`.
- Manifest entry shape is the storage convention's, exactly: `{"status":"complete","pages":{"total":N,"pagination":{"type":"filesystem"}},"taskInput":{"pipeline":"tika","version":"<tika>"}}`.
- Payload layout: `<docArtifactDir>/structure/page-%d.xhtml` and `page-%d.md`, 1-based, contiguous from `page-1`.
- `taskInput` is exactly two keys: `pipeline` = `"tika"`, `version` = `Tika.getString()` with the `"Apache Tika "` prefix stripped.
- Dependency versions are fixed: flexmark `0.64.8`, jsoup pinned to `1.22.1` reactor-wide in `dependencyManagement`.
- OCR stays off (`PDFParserConfig.OCR_STRATEGY.NO_OCR`) and embedded documents contribute no pages. Byte-determinism is a hard requirement: the same source bytes must yield byte-identical files, because the cache is content-addressed.
- Do not modify `ArtifactProducer`, `FilesystemManifestRepository`, or `ArtifactTask`'s worker loop. The only `ArtifactTask` change is which registry it builds.
- Never leave `ponytail:` comments in the code.
- Comment style: explain *why*, not *what*. Match the density of the surrounding artifact classes.
- Test command (single class): `mvn -pl <module> -am test -Dtest=<ClassName> -Dsurefire.failIfNoSpecifiedTests=false`. Multiple classes are comma-separated; `+` silently passes. Never drop `-am`. Run long builds in the background and poll.

## File Structure

| File | Responsibility |
|---|---|
| `datashare-api/.../text/artifact/Pages.java` (create) | The convention's `pages` entry field: `total` + optional `pagination` |
| `datashare-api/.../text/artifact/Pagination.java` (modify) | Reduced to `(type, ranges)`; `total` moves to `Pages` |
| `datashare-api/.../text/artifact/ManifestEntry.java` (modify) | `pagination` component becomes `pages` |
| `datashare-index/.../text/structure/StructureMarkdownExtractor.java` (create, from PoC) | Pure function: source bytes to per-page (XHTML, Markdown). No filesystem, no manifest |
| `datashare-index/.../text/artifact/StructureArtifact.java` (create) | The `Artifact` implementation: version fingerprint, directory replacement, file writes, entry |
| `datashare-index/.../text/indexing/elasticsearch/ArtifactPath.java` (modify) | Single home for the `structure/` + `page-%d.<ext>` layout |
| `datashare-app/.../text/artifact/ArtifactRegistry.java` (modify) | `withDefaults()`: the default catalog, raw then structure |
| `datashare-app/.../tasks/ArtifactTask.java:136` (modify) | Use `withDefaults()` instead of an inline catalog |
| `pom.xml`, `datashare-index/pom.xml` (modify) | flexmark dependency, jsoup pin |

Five tasks, each independently testable and committed: (1) manifest records, (2) path helpers, (3) extractor + deps, (4) the producer (including the two cases that run the real producer loop), (5) wiring.

---

### Task 1: Manifest records follow the convention (`pages` + nested `pagination`)

**Files:**
- Create: `datashare-api/src/main/java/org/icij/datashare/text/artifact/Pages.java`
- Modify: `datashare-api/src/main/java/org/icij/datashare/text/artifact/Pagination.java`
- Modify: `datashare-api/src/main/java/org/icij/datashare/text/artifact/ManifestEntry.java`
- Test: `datashare-api/src/test/java/org/icij/datashare/text/artifact/PaginationTest.java` (rewrite)
- Test: `datashare-api/src/test/java/org/icij/datashare/text/artifact/ManifestEntryTest.java` (modify 2 tests)
- Test: `datashare-index/src/test/java/org/icij/datashare/text/artifact/FilesystemManifestRepositoryTest.java:41,52` (the only other caller)

**Interfaces:**
- Consumes: nothing.
- Produces: `Pages.filesystem(int total) -> Pages`, `Pages.total() -> int`, `Pages.pagination() -> Pagination`, `Pagination.filesystem() -> Pagination`, `Pagination.type() -> String`, `Pagination.ranges() -> List<long[]>`, `ManifestEntry.paginated(Map<String,Object> taskInput, Pages pages) -> ManifestEntry`, `ManifestEntry.pages() -> Pages`.

Why this task exists: the Java records were written before the convention was finalized and use a flat `pagination: {type, total, byteRanges}`. No Java-written manifest contains that key (`RawArtifact` only emits `singleFile`/`empty` entries and `@JsonInclude(NON_NULL)` drops nulls), so the rename is free now and impossible later.

- [ ] **Step 1: Rewrite the failing test for the new records**

Replace the whole body of `PaginationTest.java` with:

```java
package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;
import java.util.List;
import static org.fest.assertions.Assertions.assertThat;

public class PaginationTest {
    private final ObjectMapper mapper = JsonObjectMapper.getMapper();

    @Test
    public void test_filesystem_pages_serialize_as_the_convention_shape() throws Exception {
        String json = mapper.writeValueAsString(Pages.filesystem(12));
        assertThat(json).isEqualTo("{\"total\":12,\"pagination\":{\"type\":\"filesystem\"}}");
    }

    @Test
    public void test_byte_ranges_pages_written_by_another_producer_are_readable() throws Exception {
        // The only reason `ranges` exists in the record: reading a docling-written entry. Nothing in
        // Java writes this scheme, so there is no factory for it.
        Pages read = mapper.readValue(
                "{\"total\":2,\"pagination\":{\"type\":\"byteRanges\",\"ranges\":[[0,10],[10,20]]}}", Pages.class);
        assertThat(read.total()).isEqualTo(2);
        assertThat(read.pagination().type()).isEqualTo("byteRanges");
        assertThat(read.pagination().ranges()).hasSize(2);
    }

    @Test
    public void test_pages_round_trip() throws Exception {
        Pages read = mapper.readValue(mapper.writeValueAsString(Pages.filesystem(3)), Pages.class);
        assertThat(read.total()).isEqualTo(3);
        assertThat(read.pagination().type()).isEqualTo("filesystem");
        assertThat(read.pagination().ranges()).isNull();
    }
}
```

In `ManifestEntryTest.java`, change the two tests that reference the old shape. Replace

```java
        assertThat(json).doesNotContain("total");
        assertThat(json).doesNotContain("pagination");
```

with

```java
        assertThat(json).doesNotContain("pages");
        assertThat(json).doesNotContain("pagination");
```

and replace the whole `test_paginated_entry_carries_pagination` test with

```java
    @Test
    public void test_paginated_entry_carries_pages() {
        ManifestEntry entry = ManifestEntry.paginated(Map.of("pipeline", "tika", "version", "3.3.0"),
                Pages.filesystem(12));
        assertThat(entry.total()).isEqualTo(12);
        assertThat(entry.pages().pagination().type()).isEqualTo("filesystem");
        assertThat(entry.contentType()).isNull();
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -pl datashare-api -am test -Dtest=PaginationTest,ManifestEntryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, compilation error `cannot find symbol: class Pages`.

- [ ] **Step 3: Create `Pages`**

```java
package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** A paginated artifact's page count and how the pages are located on disk, as the storage
 *  convention's `pages` entry field. `pagination` is absent for a single-document payload. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Pages(int total, Pagination pagination) {
    public static Pages filesystem(int total) {
        return new Pages(total, Pagination.filesystem());
    }
}
```

No `byteRanges` factory: nothing in Java writes that scheme. The `ranges` component below exists so
a docling-written entry deserializes, which is a Jackson concern, not a factory.

- [ ] **Step 4: Reduce `Pagination` to type + ranges**

Replace the record and its factories (keep the file's package and the `@JsonInclude`):

```java
/** How a paginated artifact's payload is split. `ranges` is present only for the byteRanges
 *  scheme: half-open [start, end) byte offsets into a single content file, one entry per page.
 *  Only datashare-python writes that scheme, so only `filesystem` has a factory here. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Pagination(String type, List<long[]> ranges) {
    public static Pagination filesystem() {
        return new Pagination("filesystem", null);
    }
}
```

Delete the stale cross-repo NOTE in its javadoc: this is now the reconciliation it asked for.

- [ ] **Step 5: Swap the `ManifestEntry` component**

In `ManifestEntry.java`, change the record component `Pagination pagination` to `Pages pages` (keep its position, third, so the serialized field order stays `status, taskInput, pages, ...`), and update the three places that use it:

```java
    public static ManifestEntry paginated(Map<String, Object> taskInput, Pages pages) {
        return new ManifestEntry(null, taskInput, pages, null, null, null, null);
    }

    public ManifestEntry withStatus(ManifestEntryStatus status) {
        return new ManifestEntry(status, taskInput, pages, contentType, filename, confidence, label);
    }

    public Integer total() {
        return pages == null ? null : pages.total();
    }
```

Also update the record's javadoc: "Single-file types use contentType/filename; paginated types use `pages`."

- [ ] **Step 6: Fix the one other caller**

`FilesystemManifestRepositoryTest` builds paginated entries at lines 41 and 52. Without this the
whole `datashare-index` test compilation breaks, and every later task's build with it. In both
lines, replace `Pagination.filesystem(3)` with `Pages.filesystem(3)` and `Pagination.filesystem(9)`
with `Pages.filesystem(9)`; the surrounding `ManifestEntry.paginated(Map.of(...), ...)` call and its
`.withStatus(...)` stay as they are.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `mvn -pl datashare-api -am test -Dtest=PaginationTest,ManifestEntryTest,ArtifactTypeTest,ManifestEntryStatusTest -Dsurefire.failIfNoSpecifiedTests=false`
Then: `mvn -pl datashare-index -am test -Dtest=FilesystemManifestRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS for both.

The exact-string assertion in `test_filesystem_pages_serialize_as_the_convention_shape` is safe
because `JsonObjectMapper` is a plain `ObjectMapper` with no alphabetical property sorting
(`JsonObjectMapper.java:49`, `:61-68`), so records serialize in component declaration order.

- [ ] **Step 8: Check nothing else referenced the old component**

Run: `grep -rn "\.pagination()\|Pagination\.filesystem(" --include='*.java' datashare-api/src datashare-index/src datashare-app/src | grep -v "batch\|WebQuery\|asynctasks"`
Expected: only the files touched in this task. The `batch.WebQueryPagination` hits are an unrelated
class with the same simple name: leave them alone.

- [ ] **Step 9: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/text/artifact datashare-api/src/test/java/org/icij/datashare/text/artifact datashare-index/src/test/java/org/icij/datashare/text/artifact/FilesystemManifestRepositoryTest.java
git commit -m "refactor(artifacts): record pagination as the convention's pages + nested pagination"
```

---

### Task 2: `ArtifactPath` owns the `structure/` layout

**Files:**
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ArtifactPath.java`
- Test: `datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/ArtifactPathTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `ArtifactPath.STRUCTURE_DIR` (`String`, `"structure"`), `ArtifactPath.structureDir(Path docArtifactDir) -> Path`, `ArtifactPath.structurePage(Path docArtifactDir, int page, String extension) -> Path`.

These are keyed on `docArtifactDir` (what `ArtifactContext` carries) rather than `projectRoot` + digest, mirroring how `RawArtifact` resolves `ArtifactPath.RAW_FILE`. The serving side in #2228 calls `dir(projectRoot, digest)` first anyway, for `manifest.json`.

- [ ] **Step 1: Write the failing test**

Append to `ArtifactPathTest`:

```java
    @Test
    public void test_structure_dir_is_under_the_node_dir() {
        Path docDir = ArtifactPath.dir(Path.of("/artifact/prj"), DIGEST);
        assertThat(ArtifactPath.structureDir(docDir).toString())
                .isEqualTo("/artifact/prj/6a/bb/" + DIGEST + "/structure");
    }

    @Test
    public void test_structure_page_is_one_based_and_unpadded() {
        Path docDir = ArtifactPath.dir(Path.of("/artifact/prj"), DIGEST);
        assertThat(ArtifactPath.structurePage(docDir, 1, "md").getFileName().toString())
                .isEqualTo("page-1.md");
        assertThat(ArtifactPath.structurePage(docDir, 12, "xhtml").getFileName().toString())
                .isEqualTo("page-12.xhtml");
        assertThat(ArtifactPath.structurePage(docDir, 12345, "md").getFileName().toString())
                .isEqualTo("page-12345.md");
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl datashare-index -am test -Dtest=ArtifactPathTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, compilation error `cannot find symbol: method structureDir`.

- [ ] **Step 3: Add the helpers**

In `ArtifactPath.java`, next to `RAW_FILE`:

```java
    public static final String STRUCTURE_DIR = "structure";
```

and after `manifest(...)`:

```java
    /** The structure artifact's own directory: one file per page and per format. */
    public static Path structureDir(Path docArtifactDir) {
        return docArtifactDir.resolve(STRUCTURE_DIR);
    }

    /** page-%d.<extension>, 1-based, as the convention's filesystem pagination requires. Kept
     *  here so the producer and the serving side cannot disagree on the name. */
    public static Path structurePage(Path docArtifactDir, int page, String extension) {
        return structureDir(docArtifactDir).resolve(String.format("page-%d.%s", page, extension));
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl datashare-index -am test -Dtest=ArtifactPathTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ArtifactPath.java datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/ArtifactPathTest.java
git commit -m "feat(artifacts): add the structure dir and page-N path helpers"
```

---

### Task 3: `StructureMarkdownExtractor` (Tika to per-page XHTML + Markdown)

**Files:**
- Create: `datashare-index/src/main/java/org/icij/datashare/text/structure/StructureMarkdownExtractor.java`
- Modify: `pom.xml` (`dependencyManagement`: flexmark, jsoup pin; `properties`: `flexmark.version`)
- Modify: `datashare-index/pom.xml` (flexmark dependency)
- Test: `datashare-index/src/test/java/org/icij/datashare/text/structure/StructureMarkdownExtractorTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `StructureMarkdownExtractor.Page` (record, `xhtml() -> String`, `markdown() -> String`), `new StructureMarkdownExtractor()`, `extract(InputStream source, String contentType) -> List<Page>` (throws `IOException, SAXException, TikaException`), package-private `sanitize(Element) -> String` and `toXhtml(InputStream, String) -> String` for tests.

The pipeline is carried over from the PoC (`git show poc/tika-document-structure-extraction:datashare-index/src/main/java/org/icij/datashare/text/structure/StructureMarkdownExtractor.java`), which is the reference for the comments and the constants. The only behaviour change: `extract` returns per-page pairs instead of `(whole-document xhtml, List<String> markdown)`, and the sanitized fragment that the PoC computed and discarded is now wrapped and returned as the page's XHTML.

Dependency note, load-bearing: flexmark-html2md pulls jsoup 1.15.4, which wins Maven mediation over the 1.22.1 that Tika 3.3.0 manages. The old jsoup lacks `org.jsoup.parser.TagSet`, so Tika's `JSoupParser` fails to instantiate, its SPI registration is silently dropped, and `text/html` falls back to `TextAndCSVParser`, which HTML-escapes whole documents. Hence the reactor-wide pin. Verify after the pom edit: `mvn -o -pl datashare-index dependency:list | grep jsoup` must show `1.22.1`.

- [ ] **Step 1: Add the dependencies**

In the root `pom.xml` `<properties>`, next to `<junit.version>`:

```xml
        <flexmark.version>0.64.8</flexmark.version>
```

In the root `pom.xml` `<dependencyManagement><dependencies>`, at the end:

```xml
            <dependency>
                <groupId>com.vladsch.flexmark</groupId>
                <artifactId>flexmark-html2md-converter</artifactId>
                <version>${flexmark.version}</version>
            </dependency>

            <!-- flexmark-html2md transitively pulls jsoup 1.15.4, which wins Maven mediation and
                 downgrades the jsoup that Tika 3.3.0 manages (1.22.1, per tika-parent). The older
                 jsoup lacks org.jsoup.parser.TagSet, so Tika's JSoupParser fails to instantiate, its
                 SPI registration is silently dropped, and text/html falls back to TextAndCSVParser,
                 which HTML-escapes the whole document instead of parsing its structure. Pin 1.22.1
                 (Tika's expected version) reactor-wide: datashare-app also relies on Tika. -->
            <dependency>
                <groupId>org.jsoup</groupId>
                <artifactId>jsoup</artifactId>
                <version>1.22.1</version>
            </dependency>
```

In `datashare-index/pom.xml`, after the `extract-lib` dependency:

```xml
        <dependency>
            <groupId>com.vladsch.flexmark</groupId>
            <artifactId>flexmark-html2md-converter</artifactId>
        </dependency>
```

- [ ] **Step 2: Write the failing test**

Create `StructureMarkdownExtractorTest`. This is the PoC's test adapted to `List<Page>`, plus two new cases for the per-page XHTML:

```java
package org.icij.datashare.text.structure;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.icij.datashare.text.structure.StructureMarkdownExtractor.Page;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.fest.assertions.Assertions.assertThat;

public class StructureMarkdownExtractorTest {
    private final StructureMarkdownExtractor extractor = new StructureMarkdownExtractor();

    private ByteArrayInputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> markdown(List<Page> pages) {
        return pages.stream().map(Page::markdown).toList();
    }

    @Test
    public void test_pdf_returns_one_page_per_pdf_page_without_br_noise() throws Exception {
        List<Page> pages = extractor.extract(new ByteArrayInputStream(twoPagePdf()), "application/pdf");
        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).markdown()).contains("page 1");
        assertThat(pages.get(1).markdown()).contains("page 2");
        assertThat(pages.get(0).markdown()).excludes("<br />");
    }

    @Test
    public void test_page_xhtml_is_a_parseable_xhtml_document() throws Exception {
        Page page = extractor.extract(new ByteArrayInputStream(twoPagePdf()), "application/pdf").get(0);
        assertThat(page.xhtml()).startsWith("<html xmlns=\"http://www.w3.org/1999/xhtml\">");
        assertThat(page.xhtml()).contains("<body>");
        assertThat(page.xhtml()).contains("page 1");
        // parses under the XML parser, which a bare body fragment would not
        assertThat(Jsoup.parse(page.xhtml(), "", Parser.xmlParser()).select("body").size()).isEqualTo(1);
    }

    @Test
    public void test_page_markdown_derives_from_the_same_sanitized_html() throws Exception {
        Page page = extractor.extract(
                stream("<html><body><p>plain <strong>bold</strong><script>alert(1)</script></p></body></html>"),
                "text/html").get(0);
        assertThat(page.xhtml()).excludes("script");
        assertThat(page.xhtml()).contains("<strong>bold</strong>");
        assertThat(page.markdown()).contains("**bold**");
        assertThat(page.markdown()).excludes("alert");
    }

    @Test
    public void test_underline_is_normalized_to_plain_text() throws Exception {
        Page page = extractor.extract(
                stream("<html><body><p>see <u>under</u> line</p></body></html>"), "text/html").get(0);
        assertThat(page.markdown()).contains("under");
        assertThat(page.markdown()).excludes("++under++");
    }

    @Test
    public void test_non_paginated_html_is_single_page() throws Exception {
        List<Page> pages = extractor.extract(
                stream("<html><body><h1>Title</h1><p>body</p></body></html>"), "text/html");
        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).markdown()).contains("# Title");
    }

    @Test
    public void test_extract_is_deterministic() throws Exception {
        String html = "<html><body><h1>Repeat</h1><p>same</p></body></html>";
        List<Page> first = extractor.extract(stream(html), "text/html");
        List<Page> second = extractor.extract(stream(html), "text/html");
        assertThat(markdown(first)).isEqualTo(markdown(second));
        assertThat(first.get(0).xhtml()).isEqualTo(second.get(0).xhtml());
    }

    @Test
    public void test_sanitize_strips_scripts_handlers_and_unsafe_urls() {
        String safe = extractor.sanitize(Jsoup.parseBodyFragment(
                "<p onclick=\"steal()\">keep</p>" +
                "<script>alert('xss')</script>" +
                "<a href=\"javascript:alert(1)\">link</a>" +
                "<img src=x onerror=\"alert(1)\">").body());
        assertThat(safe).excludes("script");
        assertThat(safe).excludes("onclick");
        assertThat(safe).excludes("onerror");
        assertThat(safe).excludes("javascript:");
        assertThat(safe).excludes("alert");
        assertThat(safe).excludes("steal");
        assertThat(safe).contains("keep");
    }

    @Test
    public void test_sanitize_strips_data_url_scheme() {
        String safe = extractor.sanitize(Jsoup.parseBodyFragment(
                "<img src=\"data:image/png;base64,AAAA\">keep" +
                "<a href=\"data:text/html;base64,BBBB\">link</a>").body());
        assertThat(safe).excludes("data:");
        assertThat(safe).contains("keep");
    }

    @Test
    public void test_paragraph_with_only_br_is_dropped() {
        String safe = extractor.sanitize(Jsoup.parseBodyFragment("<p><br></p><p>real</p>").body());
        assertThat(safe).excludes("<br");
        assertThat(safe).contains("real");
    }

    @Test
    public void test_relative_links_are_preserved() {
        String safe = extractor.sanitize(Jsoup.parseBodyFragment("<a href=\"page2.html\">next</a>").body());
        assertThat(safe).contains("page2.html");
    }

    @Test
    public void test_embedded_documents_are_not_split_into_extra_pages() throws Exception {
        byte[] eml = Files.readAllBytes(Path.of(Objects.requireNonNull(
                getClass().getResource("/docs/embedded_doc.eml")).toURI()));
        List<Page> pages = extractor.extract(new ByteArrayInputStream(eml), "message/rfc822");
        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).markdown()).contains("test embedded");
    }

    @Test
    public void test_generic_content_type_hint_is_ignored() throws Exception {
        // application/octet-stream is what embedded nodes often carry; passing it as a hint would
        // mislead Tika's detection, so it must be dropped and the bytes detected instead.
        List<Page> pages = extractor.extract(
                stream("<html><body><h1>Detected</h1></body></html>"), "application/octet-stream");
        assertThat(pages.get(0).markdown()).contains("# Detected");
    }

    private byte[] twoPagePdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int p = 1; p <= 2; p++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Body on page " + p + ".");
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -pl datashare-index -am test -Dtest=StructureMarkdownExtractorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, compilation error `package org.icij.datashare.text.structure does not exist`.

- [ ] **Step 4: Create the extractor**

```java
package org.icij.datashare.text.structure;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.html.HtmlMapper;
import org.apache.tika.parser.html.IdentityHtmlMapper;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.ToXMLContentHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts a document's source bytes into a deterministic per-page rendering of its structure.
 * Pipeline: Tika XHTML (un-flatten) -> split on <div class="page"> -> per-page sanitize -> flexmark
 * html2md. OCR is disabled and embedded documents do not contribute their own pages, so the same
 * bytes always yield byte-identical output (the content-addressed cache relies on it).
 */
public class StructureMarkdownExtractor {

    private static final String GENERIC_CONTENT_TYPE = "application/octet-stream";

    // Containers Tika wraps an embedded part's output in: a mail part, an archived file, or a generic
    // embedded object (PDF-in-PDF, OLE objects in Office files, ...).
    private static final String EMBEDDED_CONTAINERS = "div.embedded, div.email-entry, div.package-entry";

    private static final String XHTML_NAMESPACE = "http://www.w3.org/1999/xhtml";

    // Relaxed safelist minus <u>: keeps formatting/headings/links/lists/tables; jsoup's Cleaner strips
    // <script>/<style>, on* handlers, and unsafe URL schemes (javascript:/data:). preserveRelativeLinks
    // keeps scheme-less relative URLs (absolute javascript:/data: are still rejected by the protocol
    // allowlist). <u>/<ins> are unwrapped to plain text (avoiding flexmark's non-standard "++text++").
    private static final Safelist SAFELIST = Safelist.relaxed().removeTags("u").preserveRelativeLinks(true);

    // Stateless once built; build once rather than per page. ATX headings (not Setext) and collapsed
    // blank lines; all other options at pinned 0.64.8 defaults for deterministic output.
    private static final FlexmarkHtmlConverter MARKDOWN_CONVERTER = FlexmarkHtmlConverter.builder(
            new MutableDataSet()
                    .set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false)
                    .set(FlexmarkHtmlConverter.MAX_BLANK_LINES, 1)).build();

    /** One page in both stored formats: the sanitized XHTML and the Markdown derived from it. */
    public record Page(String xhtml, String markdown) {}

    /**
     * Parses {@code source} once and returns one {@link Page} per page of the root document. The
     * caller owns {@code source}: this method reads but does not close it.
     */
    public List<Page> extract(InputStream source, String contentType)
            throws IOException, SAXException, TikaException {
        org.jsoup.nodes.Document document = Jsoup.parse(toXhtml(source, contentType));
        List<Page> pages = new ArrayList<>();
        for (Element page : selectRootPages(document)) {
            // Sanitize once: the XHTML we store and the Markdown we derive come from the same string,
            // so the two formats can never disagree about a page's content.
            String sanitized = sanitize(page);
            pages.add(new Page(asXhtmlDocument(sanitized), MARKDOWN_CONVERTER.convert(sanitized).strip()));
        }
        return pages;
    }

    /**
     * Selects the page elements owned by the root document. Tika wraps each PDF page in
     * {@code <div class="page">}, but it also inlines embedded parts (mail attachments, archived files,
     * generic embedded objects), which carry their own page divs. Each node is extracted as its own
     * artifact, so the root must ignore its children's pages: this is the DOM analogue of
     * PageIndicesContentHandler's embeddedLevel==0 guard. Formats without pagination emit no page div,
     * so the whole body is returned as a single page.
     */
    private static List<Element> selectRootPages(org.jsoup.nodes.Document document) {
        List<Element> rootPages = new ArrayList<>();
        for (Element pageDiv : document.select("div.page")) {
            if (!isEmbedded(pageDiv)) {
                rootPages.add(pageDiv);
            }
        }
        if (rootPages.isEmpty()) {
            rootPages.add(document.body() != null ? document.body() : document);
        }
        return rootPages;
    }

    // A page div belongs to an embedded child when it is nested inside one of Tika's embedded-part
    // containers, rather than directly under the root document's body.
    private static boolean isEmbedded(Element element) {
        return !element.parents().select(EMBEDDED_CONTAINERS).isEmpty();
    }

    // Wraps a sanitized fragment in a minimal XHTML document serialized as XML, so a stored
    // page-N.xhtml is parseable as application/xhtml+xml rather than a bare body fragment.
    // prettyPrint off keeps the bytes stable, which the content-addressed cache depends on.
    private static String asXhtmlDocument(String sanitizedFragment) {
        org.jsoup.nodes.Document page = Jsoup.parse(
                "<html xmlns=\"" + XHTML_NAMESPACE + "\"><body>" + sanitizedFragment + "</body></html>");
        page.outputSettings()
                .syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml)
                .prettyPrint(false);
        return page.html();
    }

    String toXhtml(InputStream source, String contentType) throws IOException, SAXException, TikaException {
        ToXMLContentHandler xhtmlHandler = new ToXMLContentHandler();
        new AutoDetectParser().parse(source, xhtmlHandler, buildMetadata(contentType), buildParseContext());
        return xhtmlHandler.toString();
    }

    // Tika auto-detects the type from the bytes; we only pass a hint when it is specific. A generic
    // application/octet-stream (common for embedded nodes) would mislead detection, so it is dropped.
    private static Metadata buildMetadata(String contentType) {
        Metadata metadata = new Metadata();
        if (isSpecificContentType(contentType)) {
            metadata.set(Metadata.CONTENT_TYPE, contentType);
        }
        return metadata;
    }

    private static boolean isSpecificContentType(String contentType) {
        return contentType != null && !contentType.isBlank() && !GENERIC_CONTENT_TYPE.equalsIgnoreCase(contentType);
    }

    // Parse context for structure extraction: no OCR (deterministic and fast), and IdentityHtmlMapper so
    // inline formatting (bold/underline) survives Tika instead of being dropped by the DefaultHtmlMapper.
    // Embedded documents are filtered later, at the DOM level (see selectRootPages).
    private static ParseContext buildParseContext() {
        ParseContext context = new ParseContext();
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        context.set(PDFParserConfig.class, pdfConfig);
        context.set(HtmlMapper.class, new IdentityHtmlMapper());
        return context;
    }

    /**
     * Sanitizes one page (live jsoup DOM) to a safe Markdown-ready HTML subset (see SAFELIST). Empty
     * paragraphs are removed first so they do not survive conversion as stray "<br />" noise.
     */
    String sanitize(Element page) {
        removeEmptyParagraphs(page);
        return Jsoup.clean(page.html(), SAFELIST);
    }

    // Tika emits empty paragraphs (<p/> and <p><br/></p>) between blocks. Drop paragraphs that carry no
    // text, but keep those that hold an image (their text is blank yet they are not empty).
    private static void removeEmptyParagraphs(Element page) {
        for (Element paragraph : page.select("p")) {
            if (isBlankParagraph(paragraph)) {
                paragraph.remove();
            }
        }
    }

    private static boolean isBlankParagraph(Element paragraph) {
        return paragraph.text().isBlank() && paragraph.select("img").isEmpty();
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl datashare-index -am test -Dtest=StructureMarkdownExtractorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 12 tests.

If `test_page_xhtml_is_a_parseable_xhtml_document` fails on the exact prefix, print the actual value before adjusting the assertion: jsoup may order the `xmlns` attribute differently. Fix the assertion, never the wrapper's determinism.

- [ ] **Step 6: Verify the jsoup pin held**

Run: `mvn -o -pl datashare-index dependency:list | grep -i jsoup`
Expected: `org.jsoup:jsoup:jar:1.22.1`. If it shows 1.15.4, the `dependencyManagement` entry is missing or misplaced.

- [ ] **Step 7: Commit**

```bash
git add pom.xml datashare-index/pom.xml datashare-index/src/main/java/org/icij/datashare/text/structure datashare-index/src/test/java/org/icij/datashare/text/structure
git commit -m "feat(structure): add the Tika per-page XHTML and Markdown extractor"
```

---

### Task 4: `StructureArtifact` producer

**Files:**
- Create: `datashare-index/src/main/java/org/icij/datashare/text/artifact/StructureArtifact.java`
- Test: `datashare-index/src/test/java/org/icij/datashare/text/artifact/StructureArtifactTest.java` (create)

**Interfaces:**
- Consumes: `ArtifactPath.STRUCTURE_DIR`, `ArtifactPath.structureDir(Path)`, `ArtifactPath.structurePage(Path, int, String)` (Task 2); `StructureMarkdownExtractor.extract(InputStream, String) -> List<Page>` with `Page.xhtml()` / `Page.markdown()` (Task 3); `Pages.filesystem(int)` and `ManifestEntry.paginated(Map, Pages)` (Task 1); the existing `Artifact` interface, `ArtifactContext(project, document, docArtifactDir, sources)`, `ArtifactException(String, Throwable)`, and, for the last two tests, the existing `ArtifactProducer(ManifestRepository)` with `run(List<Artifact>, ArtifactContext, boolean force)` plus `FilesystemManifestRepository` (both in the same package as the test, so no imports).
- Produces: `new StructureArtifact()` implementing `Artifact`, used by Task 5.

- [ ] **Step 1: Write the failing test**

```java
package org.icij.datashare.text.artifact;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import com.fasterxml.jackson.databind.JsonNode;
import org.icij.datashare.json.JsonObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class StructureArtifactTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();

    private static final String HTML = "<html><body><h1>Title</h1><p>body</p></body></html>";

    private final Project project = Project.project("prj");
    private final Document doc = createDoc("doc-id").with(Path.of("/path/to/report.html"))
            .ofContentType("text/html").build();

    // A fresh stream per call: produce() consumes it, and the determinism test produces twice.
    private ArtifactContext contextFor(String html) throws Exception {
        SourceExtractor sources = mock(SourceExtractor.class);
        when(sources.getSource(project, doc)).thenAnswer(invocation ->
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
        return new ArtifactContext(project, doc, dir.getRoot().toPath(), sources);
    }

    private Path page(int number, String extension) {
        return ArtifactPath.structurePage(dir.getRoot().toPath(), number, extension);
    }

    @Test
    public void test_type_and_task_input() {
        StructureArtifact structure = new StructureArtifact();
        assertThat(structure.type()).isEqualTo(ArtifactType.STRUCTURE);
        assertThat(structure.taskInput().get("pipeline")).isEqualTo("tika");
        String version = (String) structure.taskInput().get("version");
        assertThat(version).excludes("Apache");
        assertThat(version.split("\\.").length).isEqualTo(3);
    }

    @Test
    public void test_produce_writes_both_formats_for_page_one() throws Exception {
        new StructureArtifact().produce(contextFor(HTML));

        assertThat(Files.readString(page(1, "md"))).contains("# Title");
        assertThat(Files.readString(page(1, "xhtml"))).contains("<h1>Title</h1>");
        assertThat(Files.exists(page(2, "md"))).isFalse();
    }

    @Test
    public void test_produce_returns_a_filesystem_paginated_entry() throws Exception {
        ManifestEntry entry = new StructureArtifact().produce(contextFor(HTML));

        assertThat(entry.total()).isEqualTo(1);
        assertThat(entry.pages().pagination().type()).isEqualTo("filesystem");
        assertThat(entry.taskInput().get("pipeline")).isEqualTo("tika");
        assertThat(entry.contentType()).isNull();
        assertThat(entry.isComplete()).isFalse(); // the producer loop stamps status, not produce()
    }

    @Test
    public void test_produce_is_byte_deterministic() throws Exception {
        new StructureArtifact().produce(contextFor(HTML));
        byte[] firstMd = Files.readAllBytes(page(1, "md"));
        byte[] firstXhtml = Files.readAllBytes(page(1, "xhtml"));

        new StructureArtifact().produce(contextFor(HTML));

        assertThat(Files.readAllBytes(page(1, "md"))).isEqualTo(firstMd);
        assertThat(Files.readAllBytes(page(1, "xhtml"))).isEqualTo(firstXhtml);
    }

    @Test
    public void test_produce_replaces_the_flat_structure_file_written_by_the_python_producer() throws Exception {
        Files.writeString(dir.getRoot().toPath().resolve(ArtifactPath.STRUCTURE_DIR), "docling markdown");

        ManifestEntry entry = new StructureArtifact().produce(contextFor(HTML));

        assertThat(Files.isDirectory(ArtifactPath.structureDir(dir.getRoot().toPath()))).isTrue();
        assertThat(Files.readString(page(1, "md"))).contains("# Title");
        assertThat(entry.total()).isEqualTo(1);
    }

    @Test
    public void test_produce_removes_stale_pages_from_a_previous_longer_run() throws Exception {
        Files.createDirectories(ArtifactPath.structureDir(dir.getRoot().toPath()));
        Files.writeString(page(9, "md"), "stale page nine");

        new StructureArtifact().produce(contextFor(HTML));

        assertThat(Files.exists(page(9, "md"))).isFalse();
        assertThat(Files.exists(page(1, "md"))).isTrue();
    }

    @Test
    public void test_produce_leaves_sibling_artifacts_alone() throws Exception {
        Files.writeString(dir.getRoot().toPath().resolve(ArtifactPath.RAW_FILE), "raw bytes");

        new StructureArtifact().produce(contextFor(HTML));

        assertThat(Files.readString(dir.getRoot().toPath().resolve(ArtifactPath.RAW_FILE))).isEqualTo("raw bytes");
    }

    @Test
    public void test_produce_fails_and_writes_nothing_when_the_source_cannot_be_read() throws Exception {
        SourceExtractor sources = mock(SourceExtractor.class);
        when(sources.getSource(project, doc)).thenThrow(new java.io.FileNotFoundException("gone"));

        try {
            new StructureArtifact().produce(new ArtifactContext(project, doc, dir.getRoot().toPath(), sources));
            org.junit.Assert.fail("expected an ArtifactException");
        } catch (ArtifactException expected) {
            // the directory must not be created before the source is known to be readable
        }
        assertThat(Files.exists(ArtifactPath.structureDir(dir.getRoot().toPath()))).isFalse();
    }

    // The two cases below go through the real producer loop, which owns the manifest write and
    // skip-if-current. ArtifactProducer and FilesystemManifestRepository are in this package.

    @Test
    public void test_manifest_written_by_the_real_producer_loop_matches_the_convention() throws Exception {
        boolean produced = new ArtifactProducer(new FilesystemManifestRepository())
                .run(List.of(new StructureArtifact()), contextFor(HTML), false);

        assertThat(produced).isTrue();
        // The repository pretty-prints the manifest (FilesystemManifestRepository.java:89 uses
        // writerWithDefaultPrettyPrinter), so assert on the parsed tree, not on raw bytes.
        JsonNode structure = JsonObjectMapper.getMapper()
                .readTree(Files.readString(dir.getRoot().toPath().resolve(ArtifactPath.MANIFEST_FILE)))
                .get("structure");
        assertThat(structure.get("status").asText()).isEqualTo("complete");
        assertThat(structure.get("pages").get("total").asInt()).isEqualTo(1);
        assertThat(structure.get("pages").get("pagination").get("type").asText()).isEqualTo("filesystem");
        assertThat(structure.get("taskInput").get("pipeline").asText()).isEqualTo("tika");
    }

    @Test
    public void test_second_producer_run_skips_a_document_that_is_already_current() throws Exception {
        ArtifactProducer producer = new ArtifactProducer(new FilesystemManifestRepository());
        producer.run(List.of(new StructureArtifact()), contextFor(HTML), false);
        // Delete the payload: if the second run regenerated, the page would come back.
        Files.delete(page(1, "md"));

        producer.run(List.of(new StructureArtifact()), contextFor(HTML), false);

        assertThat(Files.exists(page(1, "md"))).isFalse();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl datashare-index -am test -Dtest=StructureArtifactTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, compilation error `cannot find symbol: class StructureArtifact`.

- [ ] **Step 3: Write the producer**

```java
package org.icij.datashare.text.artifact;

import org.apache.commons.io.FileUtils;
import org.apache.tika.Tika;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.text.structure.StructureMarkdownExtractor;
import org.icij.datashare.text.structure.StructureMarkdownExtractor.Page;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** The structure artifact: a per-page Tika rendering written as page-N.xhtml (sanitized, the
 *  source of truth) and page-N.md (derived from it) under the document's structure/ dir. */
public class StructureArtifact implements Artifact {
    private static final ArtifactType TYPE = ArtifactType.STRUCTURE;
    // Tika.getString() returns "Apache Tika <version>"; extract-lib strips the same prefix.
    private static final String TIKA_PREFIX = "Apache Tika";

    private final StructureMarkdownExtractor extractor = new StructureMarkdownExtractor();

    @Override
    public ArtifactType type() {
        return TYPE;
    }

    @Override
    public Map<String, Object> taskInput() {
        // The pipeline plus the exact Tika version that produced the pages: a Tika upgrade changes
        // the XHTML, so skip-if-current must see already-cached pages as stale.
        return Map.of("pipeline", "tika", "version", tikaVersion());
    }

    @Override
    public ManifestEntry produce(ArtifactContext context) throws ArtifactException {
        Path docArtifactDir = context.docArtifactDir();
        try (InputStream source = context.sources().getSource(context.project(), context.document())) {
            List<Page> pages = extractor.extract(source, context.document().getContentType());
            // One current representation per type: drop whatever holds the structure path first, be it
            // a previous longer run's pages or the flat `structure` file the Python producer writes.
            // Extraction runs before this, so a failed parse leaves the cached pages untouched.
            replaceStructureDir(docArtifactDir);
            for (int index = 0; index < pages.size(); index++) {
                write(docArtifactDir, index + 1, pages.get(index));
            }
            return ManifestEntry.paginated(taskInput(), Pages.filesystem(pages.size()));
        } catch (Exception failure) {
            throw new ArtifactException("structure extraction failed for " + context.document().getId(), failure);
        }
    }

    private static void write(Path docArtifactDir, int pageNumber, Page page) throws IOException {
        Files.writeString(ArtifactPath.structurePage(docArtifactDir, pageNumber, "xhtml"),
                page.xhtml(), StandardCharsets.UTF_8);
        Files.writeString(ArtifactPath.structurePage(docArtifactDir, pageNumber, "md"),
                page.markdown(), StandardCharsets.UTF_8);
    }

    // Deletes the structure path whatever it holds (a previous run's directory, or the flat file the
    // Python producer writes) and recreates it empty. A payload that survives is not ignored:
    // createDirectories then throws FileAlreadyExistsException. Sibling types in the same document
    // dir (raw, raw.json, manifest.json) are never touched.
    private static void replaceStructureDir(Path docArtifactDir) throws IOException {
        Path structureDir = ArtifactPath.structureDir(docArtifactDir);
        FileUtils.deleteQuietly(structureDir.toFile());
        Files.createDirectories(structureDir);
    }

    private static String tikaVersion() {
        return Tika.getString().replace(TIKA_PREFIX, "").strip();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl datashare-index -am test -Dtest=StructureArtifactTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 10 tests. If the manifest assertion fails, print the file and compare with the shape
in Global Constraints before touching the assertion: the serialized order is the record component
order, so a mismatch means `ManifestEntry`'s components were reordered in Task 1.

- [ ] **Step 5: Commit**

```bash
git add datashare-index/src/main/java/org/icij/datashare/text/artifact/StructureArtifact.java datashare-index/src/test/java/org/icij/datashare/text/artifact/StructureArtifactTest.java
git commit -m "feat(structure): produce the structure artifact from the ARTIFACT stage"
```

---

### Task 5: Wire it into the default catalog

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/text/artifact/ArtifactRegistry.java`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/ArtifactTask.java:136`
- Test: `datashare-app/src/test/java/org/icij/datashare/text/artifact/ArtifactRegistryTest.java`

**Interfaces:**
- Consumes: `new StructureArtifact()` (Task 4), `new RawArtifact()` (existing).
- Produces: `ArtifactRegistry.withDefaults() -> ArtifactRegistry`.

`withDefaults()` deliberately matches the name and signature PR #2262 introduces, so the merge conflict on this line resolves to "add `StructureArtifact` to that list" rather than two divergent wirings. It also makes the default catalog unit-testable, which an inline `new ArtifactRegistry(...)` inside `runWorker` is not.

- [ ] **Step 1: Write the failing test**

Append to `ArtifactRegistryTest`:

```java
    @Test
    public void test_default_catalog_is_raw_then_structure() {
        assertThat(types(ArtifactRegistry.withDefaults().select(null))).isEqualTo(List.of("raw", "structure"));
    }

    @Test
    public void test_default_catalog_can_be_narrowed_to_one_type() {
        assertThat(types(ArtifactRegistry.withDefaults().select("structure"))).isEqualTo(List.of("structure"));
    }
```

Raw must come first: the registry keeps a `LinkedHashMap`, and structure on an embedded document reads the raw payload that raw's production caches.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl datashare-app -am test -Dtest=ArtifactRegistryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, compilation error `cannot find symbol: method withDefaults()`.

- [ ] **Step 3: Add `withDefaults()`**

In `ArtifactRegistry.java`, after the constructor:

```java
    /** The app's default catalog of Java-produced artifact types, in production order: raw first, so
     *  structure on an embedded document reads bytes raw has already cached. Shared by every caller
     *  so a newly registered producer cannot be honored by one stage and missed by another. */
    public static ArtifactRegistry withDefaults() {
        return new ArtifactRegistry(List.of(new RawArtifact(), new StructureArtifact()));
    }
```

- [ ] **Step 4: Use it in `ArtifactTask`**

Replace line 136, which currently reads

```java
        ArtifactRegistry registry = new ArtifactRegistry(List.of(new RawArtifact()));
```

with

```java
        ArtifactRegistry registry = ArtifactRegistry.withDefaults();
```

Update the comment above it: an absent `--artifacts` means all registered types, which is now raw and structure. Remove the now-false "(raw is the only one wired in this foundation)". Drop the `RawArtifact` / `List` imports if nothing else in the file uses them; the compiler warns, `mvn -pl datashare-app -am compile` confirms.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -pl datashare-app -am test -Dtest=ArtifactRegistryTest,ArtifactTaskTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 6: Run the whole artifact surface**

Run: `mvn -pl datashare-index -am test -Dtest=ArtifactPathTest,RawArtifactTest,StructureArtifactTest,StructureMarkdownExtractorTest,ArtifactProducerTest,FilesystemManifestRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false`
Then: `mvn -pl datashare-app -am test -Dtest=ArtifactRegistryTest,ArtifactTaskTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS for both. Run these in the background and poll; they take minutes.

`ArtifactChaosIntTest` and `ArtifactCoverageIntTest` need Elasticsearch and Redis (`elasticsearch:9200`, `redis:6379`); run them only if those are up. Four unrelated classes fail for environment reasons on any branch: confirm any failure also fails on `main` before treating it as yours.

- [ ] **Step 7: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/text/artifact/ArtifactRegistry.java datashare-app/src/main/java/org/icij/datashare/tasks/ArtifactTask.java datashare-app/src/test/java/org/icij/datashare/text/artifact/ArtifactRegistryTest.java
git commit -m "feat(artifacts): produce structure by default in the ARTIFACT stage"
```

---

## Verification before opening the PR

- [ ] `git log --oneline main..HEAD` shows five commits, one per task.
- [ ] `grep -rn "ponytail:" --include='*.java' datashare-api datashare-index datashare-app` is empty.
- [ ] `mvn -o -pl datashare-index dependency:list | grep -i jsoup` shows `1.22.1`.
- [ ] Full back-end build in the background: `mvn -DskipTests package`, then the artifact test classes from Task 5 Step 6.
- [ ] Manual smoke run on a real corpus, from the repo root, with a scratch dir:
  `./datashare --stages SCAN,INDEX --dataDir <dir> --artifactDir /tmp/artifacts --artifacts` then inspect one document dir: `structure/page-1.md`, `page-1.xhtml`, and `manifest.json`. INDEX only records `raw`, so structure appears after `--stages ENQUEUEIDX,ARTIFACT --artifactDir /tmp/artifacts --artifacts`.
- [ ] Confirm the diff against the spec: no serving code, no `DocumentResource` change, no `ArtifactProducer` change.

## Suggested follow-ups (do not include in this PR)

1. `datashare-python`: write `structure/content.md` instead of the flat `structure` file, and nest pagination as `pages.pagination` with `ranges`, per the convention.
2. Convention doc: add the `transcription` type, fix the trailing commas in the example JSON, and reword the `total` paragraph that contradicts the nested example.
3. `datashare-python`: make the manifest read-modify-write concurrency-safe.
