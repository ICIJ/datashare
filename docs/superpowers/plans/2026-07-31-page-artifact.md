# `page` artifact (#2227) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist a document's paginated plain extracted text as the `page` artifact type: one `pages/content.txt` per document with half-open byte ranges recorded in `manifest.json`, produced from the ARTIFACT stage.

**Architecture:** A third `Artifact` implementation beside `RawArtifact` and `StructureArtifact`. It builds an extract-lib `Extractor` per document from the run's properties, calls `extractPages` on the document's own bytes (the original file for a root, the cached `raw` payload for an embed), writes the concatenated page text once while accumulating per-page byte offsets, and returns a paginated `ManifestEntry`. `ArtifactProducer` (unchanged) stamps `COMPLETE` and writes the entry last under the per-document lock.

**Tech Stack:** Java 21, Maven multi-module (`datashare-api`, `datashare-index`, `datashare-app`), extract-lib 9.15.6 (`org.icij.extract.extractor.Extractor`), Tika 3.3.0, PDFBox 3.0.7 (test fixtures only, `provided` scope), JUnit 4 + fest-assert + Mockito.

**Spec:** `docs/superpowers/specs/2026-07-31-page-artifact-design.md`

## Global Constraints

- Branch off `feat/artifacts-structure` (#2226), branch name `feat/artifacts-page`. Never rebase onto main mid-implementation: #2226 is still moving in a parallel session.
- Manifest key and `--artifacts` token: `page`. On-disk directory: `pages/`. Payload file: `content.txt`. Never `pages` as the key, never `page-N.txt` as output.
- Pagination scheme is `byteRanges`: half-open `[start, end)` **byte** offsets, contiguous, `ranges[i][1] == ranges[i+1][0]`, first start `0`, last end equal to the file length.
- `taskInput` is exactly `{"pipeline": "tika", "version": <Tika version without the "Apache Tika " prefix>, "ocr": <run's ocr option, default true>}`. No per-document values: `Artifact.taskInput()` takes no document and its contract forbids them.
- `produce()` writes payload files only and never touches `manifest.json`; it returns an entry **without** status. `ArtifactProducer` stamps it.
- A document with zero pages returns `ManifestEntry.empty(taskInput())` and writes no `pages/` directory.
- Tests are JUnit 4 (`@Test`, `@Rule TemporaryFolder`), assertions are `org.fest.assertions.Assertions.assertThat`, mocks are Mockito, documents are built with `org.icij.datashare.text.DocumentBuilder.createDoc`.
- Test commands always carry `-Dsurefire.failIfNoSpecifiedTests=false`, comma-separated `-Dtest` values, and never drop `-am`.
- Do not add `ocr`, `embedOutput`, or any new CLI option. Do not touch serving code (`DocumentResource`, any `/artifacts` route): that is #2228.
- Commit messages follow the repo's semantic style (`feat(artifacts): …`, `test(artifacts): …`). No Claude attribution, no `ponytail:` comments left in the code.

---

### Task 1: Workspace

**Files:**
- Create: git worktree at `.claude/worktrees/artifacts-page` on new branch `feat/artifacts-page`
- Copy (gitignored, required for the build): `datashare-devenv.properties`, `docs/superpowers/specs/2026-07-31-page-artifact-design.md`, `docs/superpowers/plans/2026-07-31-page-artifact.md`

**Interfaces:**
- Consumes: nothing.
- Produces: a compiling workspace whose `HEAD` is `feat/artifacts-structure`, with `Pages`, `Pagination`, `StructureArtifact`, `ArtifactPath.structureDir/structurePage/pageFilename` and `ArtifactRegistry.withDefaults()` present.

- [ ] **Step 1: Create the worktree off the structure branch**

```bash
git worktree add -b feat/artifacts-page .claude/worktrees/artifacts-page feat/artifacts-structure
```

- [ ] **Step 2: Copy the gitignored files the build and the plan need**

`datashare-devenv.properties` is gitignored; without it jOOQ code generation fails with "No JDBC Connection configured".

```bash
cp datashare-devenv.properties .claude/worktrees/artifacts-page/
mkdir -p .claude/worktrees/artifacts-page/docs/superpowers/specs .claude/worktrees/artifacts-page/docs/superpowers/plans
cp docs/superpowers/specs/2026-07-31-page-artifact-design.md .claude/worktrees/artifacts-page/docs/superpowers/specs/
cp docs/superpowers/plans/2026-07-31-page-artifact.md .claude/worktrees/artifacts-page/docs/superpowers/plans/
```

- [ ] **Step 3: Verify the workspace compiles and the inherited API is what this plan expects**

Run, from `.claude/worktrees/artifacts-page`:

```bash
grep -n "filesystem" datashare-api/src/main/java/org/icij/datashare/text/artifact/Pages.java
grep -n "withDefaults" datashare-app/src/main/java/org/icij/datashare/text/artifact/ArtifactRegistry.java
mvn -o -pl datashare-index -am test-compile -DskipTests
```

Expected: `Pages.filesystem(int)` exists and there is **no** `byteRanges` factory; `withDefaults()` takes no argument; the build ends with `BUILD SUCCESS`. If `Pages` already has `byteRanges`, the parallel session added it: skip Task 2's implementation steps and keep only its tests.

- [ ] **Step 4: Commit nothing**

Nothing to commit yet: the worktree carries only gitignored copies. Confirm with `git status --short`, which must print nothing.

---

### Task 2: `byteRanges` factories on `Pages` and `Pagination`

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/text/artifact/Pages.java`
- Modify: `datashare-api/src/main/java/org/icij/datashare/text/artifact/Pagination.java`
- Test: `datashare-api/src/test/java/org/icij/datashare/text/artifact/PaginationTest.java`

**Interfaces:**
- Consumes: `Pages(int total, Pagination pagination)`, `Pagination(String type, List<long[]> ranges)` from #2226.
- Produces: `Pages.byteRanges(List<long[]> ranges)` (derives `total` from `ranges.size()`) and `Pagination.byteRanges(List<long[]> ranges)`. Task 6 returns `ManifestEntry.paginated(taskInput(), Pages.byteRanges(ranges))`.

- [ ] **Step 1: Write the failing tests**

Add to `PaginationTest` (keep the existing tests: the deserialization one documents why `ranges` exists at all):

```java
    @Test
    public void test_byte_ranges_pages_serialize_as_the_convention_shape() throws Exception {
        String json = mapper.writeValueAsString(Pages.byteRanges(List.of(new long[]{0, 125}, new long[]{125, 400})));
        assertThat(json).isEqualTo("{\"total\":2,\"pagination\":{\"type\":\"byteRanges\",\"ranges\":[[0,125],[125,400]]}}");
    }

    @Test
    public void test_byte_ranges_total_is_derived_from_the_range_count() {
        assertThat(Pages.byteRanges(List.of(new long[]{0, 1}, new long[]{1, 2}, new long[]{2, 3})).total()).isEqualTo(3);
        assertThat(Pages.byteRanges(List.of()).total()).isEqualTo(0);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -pl datashare-api -am test -Dtest=PaginationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, "cannot find symbol: method byteRanges".

- [ ] **Step 3: Add the two factories**

In `Pagination.java`, beside `filesystem()`:

```java
    public static Pagination byteRanges(List<long[]> ranges) {
        return new Pagination("byteRanges", ranges);
    }
```

In `Pages.java`, beside `filesystem(int)`:

```java
    /** Byte-range pagination: `total` is derived from the offsets themselves, so a producer cannot
     *  record a count that disagrees with the ranges it wrote. */
    public static Pages byteRanges(List<long[]> ranges) {
        return new Pages(ranges.size(), Pagination.byteRanges(ranges));
    }
```

Add `import java.util.List;` to `Pages.java`. Update the class comment on `Pagination` that says only `filesystem` has a factory, so it stops contradicting the code:

```java
/** How a paginated artifact's payload is split. `ranges` is present only for the byteRanges
 *  scheme: half-open [start, end) byte offsets into a single content file, one entry per page. */
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -o -pl datashare-api -am test -Dtest=PaginationTest,ManifestEntryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, no test skipped.

- [ ] **Step 5: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/text/artifact/Pages.java \
        datashare-api/src/main/java/org/icij/datashare/text/artifact/Pagination.java \
        datashare-api/src/test/java/org/icij/datashare/text/artifact/PaginationTest.java
git commit -m "feat(artifacts): add byteRanges factories deriving total from the offsets"
```

---

### Task 3: `ArtifactType.PAGE`

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/text/artifact/ArtifactType.java`
- Test: `datashare-api/src/test/java/org/icij/datashare/text/artifact/ArtifactTypeTest.java`

**Interfaces:**
- Consumes: the existing closed enum with `RAW("raw")`, `STRUCTURE("structure")`.
- Produces: `ArtifactType.PAGE` with `token() == "page"`, resolvable via `fromToken("page")` and listed by `tokens()`. Task 4's path helpers and Task 5's producer both key on it.

#2228's branch adds this same constant. Whichever lands second drops its own copy; the resolution is one line.

- [ ] **Step 1: Write the failing test**

Add to `ArtifactTypeTest`:

```java
    @Test
    public void test_page_token_is_singular_matching_the_serving_route() {
        assertThat(ArtifactType.PAGE.token()).isEqualTo("page");
        assertThat(ArtifactType.fromToken("page")).isEqualTo(ArtifactType.PAGE);
        assertThat(ArtifactType.fromToken(" PAGE ")).isEqualTo(ArtifactType.PAGE);
        assertThat(ArtifactType.tokens()).contains("page");
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o -pl datashare-api -am test -Dtest=ArtifactTypeTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, "cannot find symbol: variable PAGE".

- [ ] **Step 3: Add the constant**

```java
public enum ArtifactType {
    RAW("raw"),
    STRUCTURE("structure"),
    // The convention's type table spells this `pages`; the serving routes, the manifest key and this
    // token are `page`. The doc is what gets fixed (see the spec's follow-ups), not this spelling.
    PAGE("page");
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -o -pl datashare-api -am test -Dtest=ArtifactTypeTest,PaginationTest,ManifestEntryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/text/artifact/ArtifactType.java \
        datashare-api/src/test/java/org/icij/datashare/text/artifact/ArtifactTypeTest.java
git commit -m "feat(artifacts): declare the page artifact type"
```

---

### Task 4: `ArtifactPath` helpers for the `pages/` payload

**Files:**
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ArtifactPath.java`
- Test: `datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/ArtifactPathTest.java`

**Interfaces:**
- Consumes: `ArtifactPath.dir(Path projectRoot, String digest)`, `ArtifactPath.STRUCTURE_DIR`.
- Produces: `ArtifactPath.PAGES_DIR` (`"pages"`), `ArtifactPath.pagesDir(Path docArtifactDir)`, `ArtifactPath.pagesContent(Path docArtifactDir)`. Tasks 6-9 resolve every path through these.

Siblings of #2226's `structureDir` / `structurePage`, keyed on `docArtifactDir` the same way. **Not** the type-keyed `payloadDir(dir, type)` pair #2228's spec plans: this producer writes one type in one extension, so the dispatch would have one real caller and an unreachable arm. #2228 collapses these into its own helpers when it lands.

- [ ] **Step 1: Write the failing tests**

Add to `ArtifactPathTest`:

```java
    @Test
    public void test_pages_dir_is_under_the_node_dir() {
        Path docDir = ArtifactPath.dir(Path.of("/artifact/prj"), DIGEST);
        assertThat(ArtifactPath.pagesDir(docDir).toString())
                .isEqualTo("/artifact/prj/6a/bb/" + DIGEST + "/pages");
    }

    @Test
    public void test_pages_content_is_the_single_file_of_the_byte_ranges_scheme() {
        Path docDir = ArtifactPath.dir(Path.of("/artifact/prj"), DIGEST);
        assertThat(ArtifactPath.pagesContent(docDir).toString())
                .isEqualTo("/artifact/prj/6a/bb/" + DIGEST + "/pages/content.txt");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -pl datashare-index -am test -Dtest=ArtifactPathTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, "cannot find symbol: method pagesDir".

- [ ] **Step 3: Add the constant and the two helpers**

In `ArtifactPath.java`, after `STRUCTURE_DIR`:

```java
    public static final String PAGES_DIR = "pages";
```

and after `structurePage`:

```java
    /** The page artifact's own directory. */
    public static Path pagesDir(Path docArtifactDir) {
        return docArtifactDir.resolve(PAGES_DIR);
    }

    /** The single payload file of the byte-ranges scheme, whose per-page offsets live in the
     *  manifest entry instead of in file names. The extension is fixed by the type: page is text. */
    public static Path pagesContent(Path docArtifactDir) {
        return pagesDir(docArtifactDir).resolve("content.txt");
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -o -pl datashare-index -am test -Dtest=ArtifactPathTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ArtifactPath.java \
        datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/ArtifactPathTest.java
git commit -m "feat(artifacts): add the pages/ payload path helpers"
```

---

### Task 5: `PageArtifact` type and task input

**Files:**
- Create: `datashare-index/src/main/java/org/icij/datashare/text/artifact/PageArtifact.java`
- Create: `datashare-index/src/test/java/org/icij/datashare/text/artifact/PageArtifactTest.java`

**Interfaces:**
- Consumes: `Artifact` (`ArtifactType type()`, `Map<String,Object> taskInput()`, `ManifestEntry produce(ArtifactContext)`), `ArtifactType.PAGE`, `org.icij.datashare.PropertiesProvider`, `org.icij.datashare.cli.DatashareCliOptions.OCR_OPT`.
- Produces: `new PageArtifact(PropertiesProvider)`, `type()`, `taskInput()`. Tasks 6-9 fill in `produce()`; Task 10 registers it.

`DatashareCliOptions` is in `datashare-cli`, which `datashare-index` already depends on (`datashare-index/pom.xml:112`). `OCR_OPT` is `"ocr"` and `DEFAULT_OCR` is `true` (`DatashareCliOptions.java:107,255`).

- [ ] **Step 1: Write the failing test**

```java
package org.icij.datashare.text.artifact;

import org.icij.datashare.PropertiesProvider;
import org.junit.Test;

import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class PageArtifactTest {
    @Test
    public void test_type_is_page() {
        assertThat(new PageArtifact(new PropertiesProvider()).type()).isEqualTo(ArtifactType.PAGE);
    }

    @Test
    public void test_task_input_is_the_tika_pipeline_its_version_and_the_run_ocr_setting() {
        Map<String, Object> taskInput = new PageArtifact(new PropertiesProvider()).taskInput();

        assertThat(taskInput.get("pipeline")).isEqualTo("tika");
        assertThat(taskInput.get("ocr")).isEqualTo(true);
        String version = (String) taskInput.get("version");
        assertThat(version).excludes("Apache");
        assertThat(version.split("\\.").length).isEqualTo(3);
    }

    @Test
    public void test_task_input_records_ocr_off_when_the_run_disabled_it() {
        PropertiesProvider properties = new PropertiesProvider(Map.of("ocr", "false"));

        assertThat(new PageArtifact(properties).taskInput().get("ocr")).isEqualTo(false);
        assertThat(new PageArtifact(properties).taskInput())
                .isNotEqualTo(new PageArtifact(new PropertiesProvider()).taskInput());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -pl datashare-index -am test -Dtest=PageArtifactTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, "cannot find symbol: class PageArtifact".

- [ ] **Step 3: Write the minimal class**

```java
package org.icij.datashare.text.artifact;

import org.apache.tika.Tika;
import org.icij.datashare.PropertiesProvider;

import java.util.Map;

import static org.icij.datashare.cli.DatashareCliOptions.OCR_OPT;

/** The page artifact: a document's paginated PLAIN extracted text, written as a single
 *  pages/content.txt whose per-page half-open byte ranges live in the manifest entry. */
public class PageArtifact implements Artifact {
    private static final ArtifactType TYPE = ArtifactType.PAGE;
    // Tika.getString() returns "Apache Tika <version>"; extract-lib strips the same prefix.
    private static final String TIKA_PREFIX = "Apache Tika";

    private final PropertiesProvider propertiesProvider;

    public PageArtifact(PropertiesProvider propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
    }

    @Override
    public ArtifactType type() {
        return TYPE;
    }

    @Override
    public Map<String, Object> taskInput() {
        // The run's OCR setting, not the per-document one: taskInput() gets no document, and its
        // contract keeps per-document state out so the same doc compares equal across batches.
        return Map.of("pipeline", "tika", "version", tikaVersion(), "ocr", ocrEnabled());
    }

    @Override
    public ManifestEntry produce(ArtifactContext context) throws ArtifactException {
        throw new UnsupportedOperationException("not implemented yet");
    }

    private boolean ocrEnabled() {
        return propertiesProvider.get(OCR_OPT).map(Boolean::parseBoolean).orElse(true);
    }

    private static String tikaVersion() {
        return Tika.getString().replace(TIKA_PREFIX, "").strip();
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -o -pl datashare-index -am test -Dtest=PageArtifactTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add datashare-index/src/main/java/org/icij/datashare/text/artifact/PageArtifact.java \
        datashare-index/src/test/java/org/icij/datashare/text/artifact/PageArtifactTest.java
git commit -m "feat(artifacts): add PageArtifact with its tika task input"
```

---

### Task 6: `produce()` for a root document, with byte ranges

**Files:**
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/artifact/PageArtifact.java`
- Modify: `datashare-index/src/test/java/org/icij/datashare/text/artifact/PageArtifactTest.java`

**Interfaces:**
- Consumes: `ArtifactContext(Project project, Document document, Path docArtifactDir, SourceExtractor sources)`, `ArtifactPath.pagesDir/pagesContent`, `Pages.byteRanges`, `ManifestEntry.paginated`, extract-lib `Extractor(DocumentFactory, Options<String>)`, `Extractor.extractPages(Path)`, `Extractor.disableOcr()`, `org.icij.task.Options.from(Properties)`, `org.icij.extract.document.DocumentFactory`, `org.icij.datashare.text.Hasher`.
- Produces: a working `produce()` for `document.getExtractionLevel() <= 0`, returning `ManifestEntry.paginated(taskInput(), Pages.byteRanges(ranges))` after writing `pages/content.txt`.

The test fixture is a real two-page PDF generated with PDFBox 3.0.7, which is on `datashare-index`'s classpath at `provided` scope (verified via `mvn -pl datashare-index dependency:list`). A generated PDF beats a committed binary here: its page text is real, so the range arithmetic is meaningful, and it needs no tesseract.

- [ ] **Step 1: Write the failing tests**

Add to `PageArtifactTest` (and the imports they need):

```java
    @Rule public TemporaryFolder dir = new TemporaryFolder();

    private final Project project = Project.project("prj");

    // A two-page PDF with real text: no tesseract needed, and each page has a distinct body so the
    // byte ranges can be checked against the page they claim to delimit.
    private Path twoPagePdf() throws IOException {
        Path pdf = dir.getRoot().toPath().resolve("report.pdf");
        try (PDDocument document = new PDDocument()) {
            for (String text : List.of("Page one text", "Page two text")) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(pdf.toFile());
        }
        return pdf;
    }

    private ArtifactContext rootContext(Path source) {
        Document doc = createDoc("6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e")
                .with(source).ofContentType("application/pdf").build();
        Path docArtifactDir = dir.getRoot().toPath().resolve("docdir");
        return new ArtifactContext(project, doc, docArtifactDir, mock(SourceExtractor.class));
    }

    private Path contentTxt(ArtifactContext context) {
        return ArtifactPath.pagesContent(context.docArtifactDir());
    }

    @Test
    public void test_produce_writes_one_content_file_and_nothing_else() throws Exception {
        ArtifactContext context = rootContext(twoPagePdf());

        new PageArtifact(new PropertiesProvider()).produce(context);

        assertThat(Files.readString(contentTxt(context))).contains("Page one text").contains("Page two text");
        assertThat(ArtifactPath.pagesDir(context.docArtifactDir()).toFile().list())
                .isEqualTo(new String[]{"content.txt"});
    }

    @Test
    public void test_produce_returns_contiguous_byte_ranges_covering_the_content_file() throws Exception {
        ArtifactContext context = rootContext(twoPagePdf());

        ManifestEntry entry = new PageArtifact(new PropertiesProvider()).produce(context);

        assertThat(entry.total()).isEqualTo(2);
        assertThat(entry.pages().pagination().type()).isEqualTo("byteRanges");
        List<long[]> ranges = entry.pages().pagination().ranges();
        assertThat(ranges.get(0)[0]).isEqualTo(0);
        assertThat(ranges.get(0)[1]).isEqualTo(ranges.get(1)[0]);
        assertThat(ranges.get(1)[1]).isEqualTo(Files.size(contentTxt(context)));
        assertThat(entry.isComplete()).isFalse(); // the producer loop stamps status, not produce()
        assertThat(entry.contentType()).isNull();
    }

    private static String slice(byte[] content, long[] range) {
        return new String(content, (int) range[0], (int) (range[1] - range[0]), StandardCharsets.UTF_8);
    }

    @Test
    public void test_pages_match_the_live_extractor_page_by_page() throws Exception {
        Path pdf = twoPagePdf();
        ArtifactContext context = rootContext(pdf);
        List<String> live;
        try (Extractor extractor = new Extractor()) {
            extractor.disableOcr();
            live = extractor.extractPages(pdf);
        }

        ManifestEntry entry = new PageArtifact(new PropertiesProvider(Map.of("ocr", "false"))).produce(context);

        byte[] content = Files.readAllBytes(contentTxt(context));
        List<long[]> ranges = entry.pages().pagination().ranges();
        assertThat(ranges).hasSize(live.size());
        for (int index = 0; index < live.size(); index++) {
            assertThat(slice(content, ranges.get(index))).isEqualTo(live.get(index));
        }
    }
```

Imports to add:

```java
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.extract.extractor.Extractor;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.mockito.Mockito.mock;
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -pl datashare-index -am test -Dtest=PageArtifactTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL with `UnsupportedOperationException: not implemented yet` on the four new tests, the Task 5 tests still green.

- [ ] **Step 3: Implement `produce()` for a root document**

Replace the `produce()` stub and add the helpers:

```java
    @Override
    public ManifestEntry produce(ArtifactContext context) throws ArtifactException {
        Document document = context.document();
        try {
            List<String> pages = extractPages(document, sourcePath(context));
            return ManifestEntry.paginated(taskInput(), Pages.byteRanges(writePages(context, pages)));
        } catch (Exception failure) {
            throw new ArtifactException("page extraction failed for " + document.getId(), failure);
        }
    }

    private Path sourcePath(ArtifactContext context) throws IOException {
        return context.document().getPath();
    }

    // One Extractor per document, as the live endpoint does per request: disableOcr() is one-way, so
    // a document indexed without OCR cannot share an Extractor with one indexed with it. embedOutput
    // is deliberately left unset: this producer writes its own payload and nothing else.
    private List<String> extractPages(Document document, Path source) throws IOException {
        Hasher hasher = Hasher.valueOf(document.getId().length());
        DocumentFactory documentFactory = new DocumentFactory()
                .configure(Options.from(Map.of("digestAlgorithm", hasher.toStringWithoutDash())));
        try (Extractor extractor = new Extractor(documentFactory, Options.from(propertiesProvider.getProperties()))) {
            // Same rule as DocumentResource.getPages: a document indexed without OCR must be
            // paginated without OCR, or its pages would not match its indexed content.
            if (document.getOcrParser() == null) {
                extractor.disableOcr();
            }
            return extractor.extractPages(source);
        }
    }

    // Offsets are the byte counts actually written, so the recorded ranges cannot disagree with the
    // file: half-open [start, end), contiguous, first start 0, last end == file length.
    private static List<long[]> writePages(ArtifactContext context, List<String> pages) throws IOException {
        Files.createDirectories(ArtifactPath.pagesDir(context.docArtifactDir()));
        List<long[]> ranges = new ArrayList<>();
        long offset = 0;
        try (OutputStream out = Files.newOutputStream(ArtifactPath.pagesContent(context.docArtifactDir()))) {
            for (String page : pages) {
                byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
                out.write(bytes);
                ranges.add(new long[]{offset, offset + bytes.length});
                offset += bytes.length;
            }
        }
        return ranges;
    }
```

Add the imports: `org.icij.datashare.text.Document`, `org.icij.datashare.text.Hasher`, `org.icij.datashare.text.indexing.elasticsearch.ArtifactPath`, `org.icij.extract.document.DocumentFactory`, `org.icij.extract.extractor.Extractor`, `org.icij.task.Options`, `java.io.IOException`, `java.io.OutputStream`, `java.nio.charset.StandardCharsets`, `java.nio.file.Files`, `java.nio.file.Path`, `java.util.ArrayList`, `java.util.List`.

Task 8 adds the zero-pages branch, Task 9 turns the write above into a temp-file-then-move. Neither exists yet: do not write them here, or their tests can never be RED.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -o -pl datashare-index -am test -Dtest=PageArtifactTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 6 tests. If `test_pages_match_the_live_extractor_page_by_page` fails on a trailing-whitespace difference, do not "fix" it by trimming: the artifact must store exactly what `extractPages` returns.

- [ ] **Step 5: Commit**

```bash
git add datashare-index/src/main/java/org/icij/datashare/text/artifact/PageArtifact.java \
        datashare-index/src/test/java/org/icij/datashare/text/artifact/PageArtifactTest.java
git commit -m "feat(artifacts): produce a root document's pages as content.txt plus byte ranges"
```

---

### Task 7: `produce()` for an embedded document

**Files:**
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/artifact/PageArtifact.java`
- Modify: `datashare-index/src/test/java/org/icij/datashare/text/artifact/PageArtifactTest.java`

**Interfaces:**
- Consumes: `ArtifactContext.sources()` → `SourceExtractor.getSource(Project, Document)`, `ArtifactPath.RAW_FILE`, `Document.getExtractionLevel()`.
- Produces: `sourcePath(ArtifactContext)` resolving an embedded document to `docArtifactDir/raw`, warming that cache through `sources().getSource(...)` when it is missing, and throwing `ArtifactException` when it still is.

`docArtifactDir/raw` is exactly where extract-lib caches an embedded document's bytes: `EmbeddedDocumentExtractor.getEmbeddedPath` delegates to `EmbeddedArtifactWriter.rawPath` (`EmbeddedDocumentExtractor.java:136-137`), and `ArtifactPath.RAW_FILE` is `"raw"`. `SourceExtractor.getEmbeddedSource` writes it on a cache miss as a side effect of extracting.

- [ ] **Step 1: Write the failing tests**

```java
    // Extraction level 2 = embedded, and the root path deliberately does not exist: a test that
    // passes proves the root was never parsed.
    private ArtifactContext embeddedContext(SourceExtractor sources) {
        Document doc = createDoc("1a2b96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e")
                .with(Path.of("/absent/root.eml")).ofContentType("application/pdf")
                .withExtractionLevel((short) 2).build();
        return new ArtifactContext(project, doc, dir.getRoot().toPath().resolve("docdir"), sources);
    }

    @Test
    public void test_produce_reads_an_embedded_document_from_its_cached_raw_payload() throws Exception {
        SourceExtractor sources = mock(SourceExtractor.class);
        ArtifactContext context = embeddedContext(sources);
        Files.createDirectories(context.docArtifactDir());
        Files.copy(twoPagePdf(), context.docArtifactDir().resolve(ArtifactPath.RAW_FILE));

        ManifestEntry entry = new PageArtifact(new PropertiesProvider()).produce(context);

        assertThat(entry.total()).isEqualTo(2);
        assertThat(Files.readString(contentTxt(context))).contains("Page one text");
        verifyNoInteractions(sources); // the payload was already there: no re-extraction
    }

    @Test
    public void test_produce_extracts_the_raw_payload_when_it_is_missing() throws Exception {
        SourceExtractor sources = mock(SourceExtractor.class);
        ArtifactContext context = embeddedContext(sources);
        Path pdf = twoPagePdf();
        // getSource() writes the payload into the document's artifact dir as a side effect, which is
        // what this stub reproduces.
        when(sources.getSource(project, context.document())).thenAnswer(invocation -> {
            Files.createDirectories(context.docArtifactDir());
            Files.copy(pdf, context.docArtifactDir().resolve(ArtifactPath.RAW_FILE));
            return Files.newInputStream(pdf);
        });

        ManifestEntry entry = new PageArtifact(new PropertiesProvider()).produce(context);

        assertThat(entry.total()).isEqualTo(2);
        verify(sources).getSource(project, context.document());
    }

    @Test
    public void test_produce_fails_when_an_embedded_document_has_no_raw_payload() throws Exception {
        SourceExtractor sources = mock(SourceExtractor.class);
        ArtifactContext context = embeddedContext(sources);
        when(sources.getSource(project, context.document()))
                .thenThrow(new java.io.FileNotFoundException("/absent/root.eml"));

        try {
            new PageArtifact(new PropertiesProvider()).produce(context);
            fail("expected an ArtifactException");
        } catch (ArtifactException expected) {
            assertThat(expected.getMessage()).contains(context.document().getId());
        }
        assertThat(Files.exists(ArtifactPath.pagesDir(context.docArtifactDir()))).isFalse();
    }
```

Add imports: `static org.junit.Assert.fail`, `static org.mockito.Mockito.verify`, `static org.mockito.Mockito.verifyNoInteractions`, `static org.mockito.Mockito.when`. `DocumentBuilder.withExtractionLevel(short)` is `DocumentBuilder.java:164`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -pl datashare-index -am test -Dtest=PageArtifactTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — the embedded tests try to parse `/absent/root.eml` and raise `ArtifactException` caused by `NoSuchFileException`.

- [ ] **Step 3: Resolve an embedded document to its raw payload**

Replace `sourcePath`:

```java
    // A root's bytes are the original file; an embed's are its own cached raw payload, so an embed
    // costs one parse of itself instead of one parse of the whole root (289 root parses for a
    // 289-embed OST). getSource() writes that payload when it is missing, as a side effect of
    // extracting the embed from its root.
    private Path sourcePath(ArtifactContext context) throws IOException {
        Document document = context.document();
        if (document.getExtractionLevel() <= 0) {
            return document.getPath();
        }
        Path raw = context.docArtifactDir().resolve(ArtifactPath.RAW_FILE);
        if (Files.notExists(raw)) {
            context.sources().getSource(context.project(), document).close();
        }
        if (Files.notExists(raw)) {
            throw new IOException("no raw payload to paginate for embedded document " + document.getId());
        }
        return raw;
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -o -pl datashare-index -am test -Dtest=PageArtifactTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add datashare-index/src/main/java/org/icij/datashare/text/artifact/PageArtifact.java \
        datashare-index/src/test/java/org/icij/datashare/text/artifact/PageArtifactTest.java
git commit -m "feat(artifacts): paginate an embedded document from its cached raw payload"
```

---

### Task 8: a document with no pages records an empty entry

**Files:**
- Modify: `datashare-index/src/test/java/org/icij/datashare/text/artifact/PageArtifactTest.java`

**Interfaces:**
- Consumes: `ManifestEntry.empty(Map<String,Object> taskInput)`.
- Produces: the zero-pages early return in `produce()`, before any file is written.

A `.txt` file has no `div.page`, and `PagesContentHandler` only appends a page on `endPage`, so `extractPages` returns an empty list. `EMPTY` is terminal, so the document is recorded once and never reprocessed, and the serving side (#2228) treats it as 404 — matching what the live endpoint returns today for such a document (`[]`).

- [ ] **Step 1: Write the failing test**

```java
    @Test
    public void test_a_document_with_no_pages_is_recorded_as_empty_with_no_payload() throws Exception {
        Path txt = dir.getRoot().toPath().resolve("note.txt");
        Files.writeString(txt, "one line, no page divs");
        ArtifactContext context = rootContext(txt);

        ManifestEntry entry = new PageArtifact(new PropertiesProvider()).produce(context);

        assertThat(entry.status()).isEqualTo(ManifestEntryStatus.EMPTY);
        assertThat(entry.isTerminal()).isTrue();
        assertThat(entry.pages()).isNull();
        assertThat(entry.taskInput().get("pipeline")).isEqualTo("tika");
        assertThat(Files.exists(ArtifactPath.pagesDir(context.docArtifactDir()))).isFalse();
    }
```

This test needs a `text/plain` document, so split `rootContext` into an overload (the content type is
only a Tika hint here, but keeping it honest avoids a misleading fixture):

```java
    private ArtifactContext rootContext(Path source) {
        return rootContext(source, "application/pdf");
    }

    private ArtifactContext rootContext(Path source, String contentType) {
        Document doc = createDoc("6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e")
                .with(source).ofContentType(contentType).build();
        return new ArtifactContext(project, doc, dir.getRoot().toPath().resolve("docdir"), mock(SourceExtractor.class));
    }
```

and call `rootContext(txt, "text/plain")` in the new test.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o -pl datashare-index -am test -Dtest=PageArtifactTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL on the new test. Task 6's `produce()` writes an empty `content.txt` and returns a `paginated` entry with zero ranges, so the `status` assertion fails. Paste that failure into the report: it is the RED this task needs. If instead `extractPages` returned pages for a plain text file, stop and report — the spec's decision 6 would then be wrong.

- [ ] **Step 3: Return early when there are no pages**

In `produce()`, before `writePages`:

```java
            List<String> pages = extractPages(document, sourcePath(context));
            // Nothing to serve and nothing to write: no page divs means no pages, which is what the
            // live endpoint returns for such a document too. EMPTY is terminal, so the document is
            // recorded once and not reprocessed on every run.
            if (pages.isEmpty()) {
                return ManifestEntry.empty(taskInput());
            }
```

- [ ] **Step 4: Run the whole class to verify it passes**

Run: `mvn -o -pl datashare-index -am test -Dtest=PageArtifactTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add datashare-index/src/test/java/org/icij/datashare/text/artifact/PageArtifactTest.java
git commit -m "test(artifacts): pin the empty entry for a document with no pages"
```

---

### Task 9: replace the previous payload atomically

**Files:**
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/artifact/PageArtifact.java`
- Modify: `datashare-index/src/test/java/org/icij/datashare/text/artifact/PageArtifactTest.java`

**Interfaces:**
- Consumes: `java.nio.file.StandardCopyOption.ATOMIC_MOVE`, `java.nio.file.StandardCopyOption.REPLACE_EXISTING`.
- Produces: `writePages` writing `content.txt.tmp` and moving it onto `content.txt` once complete.

One reason, per the spec's decision 8: a regeneration that fails partway must leave the previous, already-`complete` payload intact rather than a truncated `content.txt` that the old manifest entry still advertises. Deliberately **not** #2226's directory swap: this type has exactly one payload file, and the stale-`page-N.txt` sweep that motivates the swap for `structure` would guard a producer that does not exist, for files a manifest-driven reader would never look at.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    public void test_a_failed_regeneration_leaves_the_previous_payload_untouched() throws Exception {
        ArtifactContext good = rootContext(twoPagePdf());
        new PageArtifact(new PropertiesProvider()).produce(good);
        byte[] previous = Files.readAllBytes(contentTxt(good));

        Path unreadable = dir.getRoot().toPath().resolve("gone.pdf");
        ArtifactContext broken = new ArtifactContext(project,
                createDoc(good.document().getId()).with(unreadable).ofContentType("application/pdf").build(),
                good.docArtifactDir(), mock(SourceExtractor.class));
        try {
            new PageArtifact(new PropertiesProvider()).produce(broken);
            fail("expected an ArtifactException");
        } catch (ArtifactException expected) {
            assertThat(expected.getMessage()).contains(good.document().getId());
        }

        assertThat(Files.readAllBytes(contentTxt(good))).isEqualTo(previous);
        assertThat(ArtifactPath.pagesDir(good.docArtifactDir()).toFile().list())
                .isEqualTo(new String[]{"content.txt"}); // no .tmp left behind
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o -pl datashare-index -am test -Dtest=PageArtifactTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL. On Task 6's code the failure comes from the source file being missing before any write, so the previous payload does survive; what must fail here is the mid-write case, which this test cannot reach. Read the failure output: if the test passes as written, keep it (it pins the no-`.tmp`-left-behind half) and note in the commit message that the truncation case is covered structurally, by writing to a temp file, not by a test.

- [ ] **Step 3: Write a temp file and move it into place**

Replace `writePages`:

```java
    // Writes the whole payload to a temp file in the same directory (so the move is same-filesystem
    // and therefore atomic), then swaps it in. A run that fails partway leaves the previous
    // content.txt, which is what the still-complete manifest entry advertises, instead of a
    // truncated one. Offsets are the byte counts actually written, so the recorded ranges cannot
    // disagree with the file: half-open [start, end), contiguous, first start 0, last end == length.
    private static List<long[]> writePages(ArtifactContext context, List<String> pages) throws IOException {
        Path content = ArtifactPath.pagesContent(context.docArtifactDir());
        Path temp = content.resolveSibling(content.getFileName() + ".tmp");
        Files.createDirectories(content.getParent());
        List<long[]> ranges = new ArrayList<>();
        long offset = 0;
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                for (String page : pages) {
                    byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
                    out.write(bytes);
                    ranges.add(new long[]{offset, offset + bytes.length});
                    offset += bytes.length;
                }
            }
            Files.move(temp, content, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            // A half-written temp file must never survive this call, whether the write or the move failed.
            Files.deleteIfExists(temp);
        }
        return ranges;
    }
```

Add the import `java.nio.file.StandardCopyOption`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -o -pl datashare-index -am test -Dtest=PageArtifactTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add datashare-index/src/main/java/org/icij/datashare/text/artifact/PageArtifact.java \
        datashare-index/src/test/java/org/icij/datashare/text/artifact/PageArtifactTest.java
git commit -m "feat(artifacts): write the page payload through a temp file so a failed run keeps the old one"
```

---

### Task 10: register `page` in the default catalog

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/text/artifact/ArtifactRegistry.java`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/ArtifactTask.java:135`
- Test: `datashare-app/src/test/java/org/icij/datashare/text/artifact/ArtifactRegistryTest.java`

**Interfaces:**
- Consumes: `ArtifactRegistry(List<Artifact>)`, `RawArtifact`, `StructureArtifact`, `PageArtifact(PropertiesProvider)`.
- Produces: `ArtifactRegistry.withDefaults(PropertiesProvider)` returning raw, structure, page in that order. This replaces the no-arg `withDefaults()` that #2226 introduced; #2225's INDEX call site will need the same one-argument update when it rebases.

- [ ] **Step 1: Write the failing tests**

Update the two `withDefaults` tests in `ArtifactRegistryTest` and add one:

```java
    @Test
    public void test_default_catalog_is_raw_then_structure_then_page() {
        assertThat(types(ArtifactRegistry.withDefaults(new PropertiesProvider()).select(null)))
                .isEqualTo(List.of("raw", "structure", "page"));
    }

    @Test
    public void test_default_catalog_can_be_narrowed_to_one_type() {
        assertThat(types(ArtifactRegistry.withDefaults(new PropertiesProvider()).select("page")))
                .isEqualTo(List.of("page"));
    }
```

Add `import org.icij.datashare.PropertiesProvider;`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -pl datashare-app -am test -Dtest=ArtifactRegistryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `withDefaults(PropertiesProvider)` not found.

- [ ] **Step 3: Add the parameter and the third producer**

In `ArtifactRegistry`:

```java
    /** The single definition of the app's default catalog of Java-produced artifact types. Order
     *  matters: raw first, so structure and page on an embedded document read bytes raw's
     *  production has already cached. Takes the properties because page builds an extractor from
     *  the run's options (OCR in particular); raw and structure need none. */
    public static ArtifactRegistry withDefaults(PropertiesProvider propertiesProvider) {
        return new ArtifactRegistry(List.of(new RawArtifact(), new StructureArtifact(), new PageArtifact(propertiesProvider)));
    }
```

Add `import org.icij.datashare.PropertiesProvider;`. In `ArtifactTask.java:135`, pass the field the task already holds:

```java
        ArtifactRegistry registry = ArtifactRegistry.withDefaults(propertiesProvider);
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -o -pl datashare-app -am test -Dtest=ArtifactRegistryTest,ArtifactTaskTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. `ArtifactTaskTest` exercises the ARTIFACT loop end to end, so a bare `--artifacts` run now produces three types; if one of its assertions counted files in a document dir, update the expectation rather than the production code.

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/text/artifact/ArtifactRegistry.java \
        datashare-app/src/main/java/org/icij/datashare/tasks/ArtifactTask.java \
        datashare-app/src/test/java/org/icij/datashare/text/artifact/ArtifactRegistryTest.java
git commit -m "feat(artifacts): produce page by default in the ARTIFACT stage"
```

---

### Task 11: the manifest, through the real producer

**Files:**
- Modify: `datashare-index/src/test/java/org/icij/datashare/text/artifact/PageArtifactTest.java`

**Interfaces:**
- Consumes: `ArtifactProducer(ManifestRepository)`, `FilesystemManifestRepository`, `ArtifactPath.MANIFEST_FILE`, `org.icij.datashare.json.JsonObjectMapper`.
- Produces: nothing new. This is the end-to-end proof that the on-disk manifest matches the storage convention and that skip-if-current works for this type.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    public void test_the_written_manifest_matches_the_convention_shape() throws Exception {
        ArtifactContext context = rootContext(twoPagePdf());
        ArtifactProducer producer = new ArtifactProducer(new FilesystemManifestRepository());

        assertThat(producer.run(List.of(new PageArtifact(new PropertiesProvider())), context, false)).isTrue();

        JsonNode manifest = JsonObjectMapper.getMapper()
                .readTree(context.docArtifactDir().resolve(ArtifactPath.MANIFEST_FILE).toFile());
        JsonNode entry = manifest.get("page");
        assertThat(entry.get("status").asText()).isEqualTo("complete");
        assertThat(entry.get("pages").get("total").asInt()).isEqualTo(2);
        assertThat(entry.get("pages").get("pagination").get("type").asText()).isEqualTo("byteRanges");
        assertThat(entry.get("pages").get("pagination").get("ranges").size()).isEqualTo(2);
        assertThat(entry.get("taskInput").get("pipeline").asText()).isEqualTo("tika");
        assertThat(entry.get("taskInput").get("ocr").asBoolean()).isTrue();
        assertThat(entry.has("contentType")).isFalse();
        assertThat(entry.has("filename")).isFalse();
    }

    @Test
    public void test_a_second_run_skips_a_document_already_produced_with_the_same_task_input() throws Exception {
        ArtifactContext context = rootContext(twoPagePdf());
        ArtifactProducer producer = new ArtifactProducer(new FilesystemManifestRepository());
        producer.run(List.of(new PageArtifact(new PropertiesProvider())), context, false);
        Files.delete(contentTxt(context)); // if the second run produced again, it would be back

        assertThat(producer.run(List.of(new PageArtifact(new PropertiesProvider())), context, false)).isTrue();

        assertThat(Files.exists(contentTxt(context))).isFalse();
    }
```

Add imports: `com.fasterxml.jackson.databind.JsonNode`, `org.icij.datashare.json.JsonObjectMapper`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -pl datashare-index -am test -Dtest=PageArtifactTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL. Both are expected to pass on the code as it stands, since `ArtifactProducer` is unchanged; run them first anyway. If the manifest test fails, read the actual JSON in the failure output and fix `PageArtifact`, not the assertion, unless the difference is `status` (which `ArtifactProducer` owns).

- [ ] **Step 3: No production change**

These tests document existing behaviour. Change nothing unless Step 2 exposed a real mismatch with the convention shape in the spec's "Data flow" section.

- [ ] **Step 4: Run the whole suite for the touched modules**

```bash
mvn -o -pl datashare-index -am test -Dtest=PageArtifactTest,ArtifactPathTest,StructureArtifactTest,RawArtifactTest,ArtifactProducerTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -pl datashare-api -am test -Dtest=PaginationTest,ManifestEntryTest,ArtifactTypeTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -pl datashare-app -am test -Dtest=ArtifactRegistryTest,ArtifactTaskTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: all PASS. Four datashare-app test classes fail for environment reasons on any branch (see the repo's known local-test gotchas); none of them is in this list, so any failure here is yours.

- [ ] **Step 5: Commit**

```bash
git add datashare-index/src/test/java/org/icij/datashare/text/artifact/PageArtifactTest.java
git commit -m "test(artifacts): assert the page manifest entry and skip-if-current end to end"
```

---

### Task 12: stop leaking `complete` and `terminal` into manifest.json

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/text/artifact/ManifestEntry.java:36-42`
- Test: `datashare-api/src/test/java/org/icij/datashare/text/artifact/ManifestEntryTest.java`
- Test: `datashare-index/src/test/java/org/icij/datashare/text/artifact/PageArtifactTest.java`

**Interfaces:**
- Consumes: `ManifestEntry` (record with `isComplete()` / `isTerminal()` convenience predicates), `JsonObjectMapper.getMapper()`.
- Produces: no API change. `isComplete()` and `isTerminal()` stay callable from Java; they simply stop appearing in the serialized JSON.

Added after Task 11, which discovered the defect. Jackson treats the two `is`-prefixed predicates as bean properties and writes them beside the record components, so a written entry carries `"complete": true` and `"terminal": true` — neither of which is in the storage convention that datashare-python also reads. The defect predates this branch (`ManifestEntry` shipped in #2224 and is on main), so `raw` entries in production already carry them; the user chose to fix it here rather than defer.

Backward compatibility is already safe and needs no migration: `JsonObjectMapper.java:67` sets `FAIL_ON_UNKNOWN_PROPERTIES` to false and `FilesystemManifestRepository.java:28` uses that shared mapper, so manifests already on disk carrying the two keys keep deserializing. `@JsonIgnore` on a getter suppresses the property in both directions, which is what we want here.

- [ ] **Step 1: Write the failing tests**

In `ManifestEntryTest`, asserting the serialized form of a stamped entry:

```java
    @Test
    public void test_convenience_predicates_are_not_serialized() throws Exception {
        ManifestEntry entry = ManifestEntry.paginated(Map.of("pipeline", "tika"), Pages.filesystem(2))
                .withStatus(ManifestEntryStatus.COMPLETE);

        String json = JsonObjectMapper.getMapper().writeValueAsString(entry);

        // The colon matters: the serialized entry legitimately contains the VALUE "complete" as its
        // status, so excluding the bare quoted word could never pass. What must be absent is a
        // FIELD named complete or terminal.
        assertThat(json).excludes("\"complete\":").excludes("\"terminal\":");
        assertThat(json).contains("\"status\":\"complete\"");
    }
```

In `PageArtifactTest`, strengthening Task 11's end-to-end manifest assertion so the on-disk file is pinned too. Add to `test_the_written_manifest_matches_the_convention_shape`:

```java
        assertThat(entry.has("complete")).isFalse();
        assertThat(entry.has("terminal")).isFalse();
```

- [ ] **Step 2: Run both tests to verify they fail**

```bash
mvn -o -pl datashare-api -am test -Dtest=ManifestEntryTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -pl datashare-index -am test -Dtest=PageArtifactTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: both FAIL. The first on the `excludes` assertions, the second on `has("complete")` being true. Capture both verbatim: this is the RED that proves the leak is real.

- [ ] **Step 3: Suppress the two properties**

In `ManifestEntry.java`, annotate both predicates:

```java
    /** Convenience predicate for callers, not part of the stored shape: the convention's entry
     *  carries `status`, and Jackson would otherwise write this getter as a `complete` field. */
    @JsonIgnore
    public boolean isComplete() {

    @JsonIgnore
    public boolean isTerminal() {
```

Add `import com.fasterxml.jackson.annotation.JsonIgnore;`. Change nothing else: the methods keep their bodies and stay callable.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
mvn -o -pl datashare-api -am test -Dtest=ManifestEntryTest,PaginationTest,ArtifactTypeTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -pl datashare-index -am test -Dtest=PageArtifactTest,RawArtifactTest,StructureArtifactTest,ArtifactProducerTest,FilesystemManifestRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: all PASS. `FilesystemManifestRepositoryTest` and `ArtifactProducerTest` matter here: they round-trip entries through the mapper, so they would catch a predicate that stopped working or an entry that no longer deserializes.

- [ ] **Step 5: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/text/artifact/ManifestEntry.java \
        datashare-api/src/test/java/org/icij/datashare/text/artifact/ManifestEntryTest.java \
        datashare-index/src/test/java/org/icij/datashare/text/artifact/PageArtifactTest.java
git commit -m "fix(artifacts): keep the convenience predicates out of manifest.json"
```

---

## Known test gap

Nothing asserts that `disableOcr()` is actually called for a document whose `ocr_parser` metadata is
absent. Proving it needs either a tesseract-dependent image fixture (environment-flaky) or an
injectable extractor seam whose test would verify a mock call rather than a behaviour. The run-level
`ocr` value is covered by Task 5, and the OCR-off text path by Task 6's parity test. If this gap is
unacceptable at review time, the smallest honest fix is a `protected Extractor createExtractor(Document)`
seam mirroring `IndexTask.java:132` and `ArtifactTask.createSourceExtractor`, plus one spy-based test.

## Out of scope, do not add

- Any `/artifacts/page` route, `ArtifactReader`, or change to `DocumentResource` (#2228).
- Writing extract-lib's `pages.json`, or any change to `/documents/pages` (spec decision 9).
- A CLI option to choose the pagination scheme, or an OCR flag of any kind.
- Fixing the embed-text bleed described in the spec's known limitations: that fix belongs in extract-lib.
