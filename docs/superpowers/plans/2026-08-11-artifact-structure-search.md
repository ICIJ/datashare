# Artifact Structure Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `GET /:project/artifacts/structure/:id/search`, counting a query's occurrences per markdown page of a document's structure artifact, so the client's Text tab can search what it renders.

**Architecture:** Three layers, each independently testable. `ContentOccurrences` ports the folding and counting rules of the Painless script `/documents/searchContent` runs inside Elasticsearch. `StructureSearch` walks a document's pages through the existing `ArtifactReader`, one page resident at a time. `ArtifactResource` gets one route and one extracted format helper it shares with the page route.

**Tech Stack:** Java 17 records, fluent-http routing, Jackson via fluent-http's `TypeConvert`, JUnit 4, Fest assertions, Mockito, Maven.

Spec: `docs/superpowers/specs/2026-08-11-artifact-structure-search-design.md`.

## Global Constraints

- **Branch.** The spec says branch off `main`, which is where PR #2306 merged on 2026-08-11. The standing rule is no feature branch unless asked, so commits land on the current branch by default. If this needs its own PR the way #2306 did, create `feat/artifacts-structure-search` off `main` before Task 1.
- **Commits.** Use the `/commit` skill, never a raw `git commit`. Each task's commit message is given in its final step.
- **No `ponytail:` comments** anywhere in the diff.
- **Code style** is `~/.claude/skills/pirhoo-style/java.md`: 4-space indent, explicit imports in the order third-party then project then `java.*` then `static`, braces always, ~120-char lines, guard clauses with early return and no `else` ladder, `private final` fields assigned in the constructor, `private` constructor on a pure-static helper, `UPPER_SNAKE_CASE` constants. Methods stay at or under 5 statements, classes under 100 lines, methods take at most 4 parameters. Route methods are the one exception on parameter count, forced by fluent-http's binding, exactly as the sibling routes in the same file already are.
- **Comments explain why**, never what the line does. Javadoc goes on public API a caller can misuse.
- **Test naming** is `test_` plus a snake_case sentence stating the behavior. Test classes carry a class-level Javadoc naming the invariant they cover.
- **Test command shape:** `mvn -pl <module> -am test -Dtest='A,B' -Dsurefire.failIfNoSpecifiedTests=false`. Three traps, all verified: never drop `-am` (Liquibase falls off the test classpath and ~55 spurious errors appear); `-Dtest` needs **commas**, because a `+`-separated list matches zero tests and still exits 0, a fake green; judge the result by the **"Tests run:"** line, never by the exit code.
- **Never block on Maven.** Run it in the background and poll, reporting each check.
- **`Assert.assertThrows` does not compile in `datashare-index` tests** (`commons-test` drags `junit-dep:4.9` ahead of `junit:4.13.2` on that module). No task here needs it; do not reach for it.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `datashare-index/src/main/java/org/icij/datashare/text/ContentOccurrences.java` | **New.** Folds text and counts non-overlapping occurrences, exactly as the Painless script does. No I/O, no artifact knowledge. Lives in the module that ships that script as a resource. |
| `datashare-index/src/test/java/org/icij/datashare/text/ContentOccurrencesTest.java` | **New.** Pins each ported quirk so a cleanup cannot silently break parity with Elasticsearch. |
| `datashare-index/src/main/java/org/icij/datashare/text/artifact/StructureSearch.java` | **New.** Decides what is searchable and walks the pages through `ArtifactReader`. Owns the response records. |
| `datashare-index/src/test/java/org/icij/datashare/text/artifact/StructureSearchTest.java` | **New.** Real files under `TemporaryFolder`, per the convention `ArtifactReaderTest` set. |
| `datashare-app/src/main/java/org/icij/datashare/web/ArtifactResource.java` | **Modify.** One route, one shared format helper, one shared error builder. |
| `datashare-app/src/test/java/org/icij/datashare/web/ArtifactResourceTest.java` | **Modify.** HTTP shell: status codes, JSON shape, gate ordering, route precedence. |

`ArtifactReader` is not touched. No new dependency. No Guice binding change: `ArtifactResource` already hand-wires its reader, matching the other three `FilesystemManifestRepository` call sites.

---

## Task 1: `ContentOccurrences`

**Files:**
- Create: `datashare-index/src/main/java/org/icij/datashare/text/ContentOccurrences.java`
- Test: `datashare-index/src/test/java/org/icij/datashare/text/ContentOccurrencesTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `public static int ContentOccurrences.count(String content, String query)` and package-private `static String ContentOccurrences.fold(String input)`.

**Reference, read this before writing anything:** `datashare-index/src/main/resources/searchOccurrences.painless.java`. Lines 41-52 are `getOffsets`, lines 28-39 are `normalizeLetters`. This task is a port of those two functions, quirks included. The quirks are the requirement, not an accident: `/documents/searchContent` and this endpoint search the same documents in the same UI, and a "better" fold here makes them disagree.

- [ ] **Step 1: Write the failing test**

Create `datashare-index/src/test/java/org/icij/datashare/text/ContentOccurrencesTest.java`:

```java
package org.icij.datashare.text;

import org.junit.Test;

import java.util.Locale;

import static org.fest.assertions.Assertions.assertThat;

/** Pins the folding and counting rules ContentOccurrences ports from
 *  searchOccurrences.painless.java: every test here fails if the two drift apart. */
public class ContentOccurrencesTest {
    @Test
    public void test_matching_ignores_case() {
        assertThat(ContentOccurrences.count("Data and DATA", "data")).isEqualTo(2);
    }

    // Escapes rather than literal accented characters throughout this class: these tests turn
    // on whether an accent is precomposed or decomposed, which a literal does not show and an
    // editor can silently renormalize, inverting the next test without touching its source.
    @Test
    public void test_a_precomposed_accent_folds_to_its_base_letter() {
        // "é" as U+00E9 is a LOWERCASE_LETTER, so the script normalizes it and drops the mark.
        assertThat(ContentOccurrences.count("r\u00e9sum\u00e9", "resume")).isEqualTo(1);
    }

    @Test
    public void test_a_decomposed_accent_does_not_fold() {
        // The same word written "e" + U+0301: the combining acute is NON_SPACING_MARK, not
        // LOWERCASE_LETTER, so the script's else branch keeps it and the mark breaks the match.
        // Wrong on its face, and load-bearing: "fixing" it makes this search disagree with
        // /documents/searchContent on the same document.
        assertThat(ContentOccurrences.count("re\u0301sume", "resume")).isEqualTo(0);
    }

    @Test
    public void test_a_caseless_script_is_left_untouched() {
        // Arabic letters are OTHER_LETTER, so no NFKD runs: alef-with-hamza (U+0623) never
        // decomposes into a bare alef (U+0627), so the two spellings of "ahmad" stay as
        // distinct here as they are in Elasticsearch.
        assertThat(ContentOccurrences.count("\u0623\u062d\u0645\u062f", "\u0623\u062d\u0645\u062f")).isEqualTo(1);
        assertThat(ContentOccurrences.count("\u0623\u062d\u0645\u062f", "\u0627\u062d\u0645\u062f")).isEqualTo(0);
    }

    @Test
    public void test_matches_do_not_overlap() {
        // The script steps by the query's length, so "aaaa" holds two "aa", not three.
        assertThat(ContentOccurrences.count("aaaa", "aa")).isEqualTo(2);
    }

    @Test
    public void test_the_query_is_matched_literally_not_as_a_regex() {
        // A regex would match "abc" too, and every user typing a dot would get a wrong count.
        assertThat(ContentOccurrences.count("a.c and abc", "a.c")).isEqualTo(1);
    }

    @Test
    public void test_a_compatibility_ligature_expands_when_folded() {
        // NFKD is the compatibility form, so "ﬁ" (U+FB01) becomes "fi" and the folded string
        // outgrows its source. Harmless for a count, and exactly why the script's offsets drift,
        // which is why offsets are not part of this endpoint's contract.
        assertThat(ContentOccurrences.count("of\ufb01ce", "office")).isEqualTo(1);
    }

    @Test
    public void test_an_empty_query_counts_nothing_instead_of_hanging() {
        // The script's loop never advances on an empty query. Serving code rejects a blank one,
        // so this guards the library against its next caller, not the route.
        assertThat(ContentOccurrences.count("anything", "")).isEqualTo(0);
    }

    @Test
    public void test_ascii_folds_to_lowercase_and_nothing_else() {
        // The ASCII fast path must produce exactly what the script's two branches produce.
        assertThat(ContentOccurrences.fold("Hello, World! 42")).isEqualTo("hello, world! 42");
    }

    @Test
    public void test_folding_ignores_the_jvm_default_locale() {
        Locale previous = Locale.getDefault();
        try {
            // Under tr-TR, "I".toLowerCase() is the dotless "ı", which would stop INDEX from
            // matching index. Locale.ROOT pins the fold so a deployment's locale cannot change
            // what a search finds.
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(ContentOccurrences.count("INDEX", "index")).isEqualTo(1);
        } finally {
            Locale.setDefault(previous);
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run in the background, then poll:

```bash
mvn -pl datashare-index -am test -Dtest='ContentOccurrencesTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: **compilation failure**, `cannot find symbol: class ContentOccurrences`. Show that output before writing any production code. A run that reports "Tests run: 0" with exit code 0 is the fake green described in Global Constraints, not a pass.

- [ ] **Step 3: Write the implementation**

Create `datashare-index/src/main/java/org/icij/datashare/text/ContentOccurrences.java`:

```java
package org.icij.datashare.text;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Counts a query's occurrences the way {@code /documents/searchContent} does, so a search over
 * artifacts folds text exactly as Elasticsearch folds it. Every rule here is a port of
 * {@code datashare-index/src/main/resources/searchOccurrences.painless.java}, quirks included:
 * the two search the same documents in the same UI, so a rule improved on one side alone shows up
 * as two counters disagreeing about one word. Each quirk is pinned by a test.
 */
public class ContentOccurrences {
    /**
     * Below this, a char is NFKD-stable and carries no diacritic, so both branches of the script's
     * fold produce the char itself: skipping the Normalizer there is a speed-up, not a change of
     * meaning. It does not reach Cyrillic or Greek, whose letters are all LOWERCASE_LETTER, so a
     * large document in those scripts still pays one Normalizer call per letter. Bounded by
     * StructureMarkdownExtractor's 16M-char output cap, and the first thing to measure if an
     * in-document search ever feels slow.
     */
    private static final char ASCII_LIMIT = 0x80;

    private ContentOccurrences() {}

    /** Non-overlapping occurrences of {@code query} in {@code content}, both folded first. */
    public static int count(String content, String query) {
        // The script's loop never advances on an empty query, so it would spin forever. Serving
        // code rejects a blank query before calling, but a hang is too sharp an edge to leave here.
        if (query.isEmpty()) {
            return 0;
        }
        return occurrences(fold(content), fold(query), query.length());
    }

    /**
     * Lowercased, then NFKD-normalized and stripped of non-spacing marks for {@code LOWERCASE_LETTER}
     * chars only. Everything else passes through, which is why a decomposed accent survives and a
     * caseless script is untouched: {@code normalizeLetters} tests that one type and nothing else.
     * {@link Locale#ROOT} rather than the default, so folding cannot depend on where the JVM runs;
     * the script's own {@code toLowerCase()} takes the Elasticsearch node's default.
     */
    static String fold(String input) {
        StringBuilder folded = new StringBuilder(input.length());
        for (char character : input.toLowerCase(Locale.ROOT).toCharArray()) {
            appendFolded(folded, character);
        }
        return folded.toString();
    }

    // Steps by the raw query's length, as the script does, not by the folded one: a query whose
    // fold expands (a ligature) then finds overlapping matches, in Elasticsearch today and here.
    private static int occurrences(String content, String query, int step) {
        int count = 0;
        for (int at = content.indexOf(query); at != -1; at = content.indexOf(query, at + step)) {
            count++;
        }
        return count;
    }

    private static void appendFolded(StringBuilder folded, char character) {
        if (character < ASCII_LIMIT || Character.getType(character) != Character.LOWERCASE_LETTER) {
            folded.append(character);
            return;
        }
        appendWithoutMarks(folded, Normalizer.normalize(String.valueOf(character), Normalizer.Form.NFKD));
    }

    private static void appendWithoutMarks(StringBuilder folded, String normalized) {
        for (char character : normalized.toCharArray()) {
            if (Character.getType(character) != Character.NON_SPACING_MARK) {
                folded.append(character);
            }
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
mvn -pl datashare-index -am test -Dtest='ContentOccurrencesTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0`. Read that line; do not trust the exit code alone.

- [ ] **Step 5: Commit**

Use the `/commit` skill with this message:

```
feat(artifacts): port the searchContent folding rules to Java
```

---

## Task 2: `StructureSearch`

**Files:**
- Create: `datashare-index/src/main/java/org/icij/datashare/text/artifact/StructureSearch.java`
- Test: `datashare-index/src/test/java/org/icij/datashare/text/artifact/StructureSearchTest.java`

**Interfaces:**
- Consumes: `ContentOccurrences.count(String content, String query)` from Task 1. From existing code, `ArtifactReader.servableEntry(Path, ArtifactType)`, `ArtifactReader.servableTotal(Path, ArtifactType, ManifestEntry)`, `ArtifactReader.page(Path, ArtifactType, ManifestEntry, int, String)` and `ArtifactReader.formats(Path, ArtifactType, ManifestEntry, Collection<String>)`.
- Produces: `new StructureSearch(ArtifactReader reader, Path docArtifactDir, String extension)`, its method `Hits search(String query) throws IOException`, and the records `StructureSearch.Hits(int count, List<PageHits> hits)` and `StructureSearch.PageHits(int page, int count)`.

**Why a per-request object rather than a shared one:** the document dir and the requested format are fixed for a whole scan. Holding them as fields keeps every helper at or under the four-parameter limit; passing them instead would put six arguments on the innermost method. `ArtifactResource` builds one per request, which is a cheap object.

**What this suite deliberately does not test:** a malformed `manifest.json`, an unknown status, an unknown pagination scheme, an unreadable payload. Those are `ArtifactReader`'s rules and `ArtifactReaderTest` already owns them; `StructureSearch` only propagates the null they produce. Restating them here would be the duplication commit `5fde37c1a` ("test(artifacts): drop route tests that restate the reader's own rules") removed from #2306. Do not add them back.

- [ ] **Step 1: Write the failing test**

Create `datashare-index/src/test/java/org/icij/datashare/text/artifact/StructureSearchTest.java`:

```java
package org.icij.datashare.text.artifact;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

/** Covers one invariant: a search answers only for a document whose structure artifact is servable
 *  in the requested format, and then counts every page that manifest advertises. */
public class StructureSearchTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();
    private final ManifestRepository manifests = new FilesystemManifestRepository();
    private final ArtifactReader reader = new ArtifactReader(manifests);

    private StructureSearch markdownSearch() {
        return new StructureSearch(reader, dir.getRoot().toPath(), "md");
    }

    // Written through ArtifactPath, never through an inline page-N format: a duplicated format
    // string would let writer and reader drift together and hide a naming bug from these tests.
    private Path writePages(String extension, String... pages) throws Exception {
        Path node = dir.getRoot().toPath();
        Files.createDirectories(ArtifactPath.payloadDir(node, ArtifactType.STRUCTURE));
        for (int page = 1; page <= pages.length; page++) {
            Files.writeString(ArtifactPath.payloadPage(node, ArtifactType.STRUCTURE, page, extension), pages[page - 1]);
        }
        return node;
    }

    // Advertises `total` pages regardless of how many were written, so a test can claim more.
    private void completeManifest(int total) throws Exception {
        manifests.put(dir.getRoot().toPath(), ArtifactType.STRUCTURE.token(),
                ManifestEntry.paginated(Map.of(), total).withStatus(ManifestEntryStatus.COMPLETE));
    }

    @Test
    public void test_search_is_null_without_a_manifest() throws Exception {
        writePages("md", "data");
        assertThat(markdownSearch().search("data")).isNull();
    }

    @Test
    public void test_search_is_null_when_the_entry_is_never_stamped_complete() throws Exception {
        Path node = writePages("md", "data");
        // Paginated and on disk, but no status: the strict store reads a half-written artifact as
        // absent rather than serving what a crashed producer happened to leave behind.
        manifests.put(node, ArtifactType.STRUCTURE.token(), ManifestEntry.paginated(Map.of(), 1));
        assertThat(markdownSearch().search("data")).isNull();
    }

    @Test
    public void test_search_is_null_when_the_pages_block_has_no_usable_total() throws Exception {
        Path node = writePages("md", "data");
        // Pages.total is a primitive, so an absent total deserializes to 0: complete, but malformed.
        Files.writeString(node.resolve(ArtifactPath.MANIFEST_FILE),
                "{\"structure\": {\"status\": \"complete\", \"taskInput\": {}, "
                        + "\"pages\": {\"pagination\": {\"type\": \"filesystem\"}}}}");
        assertThat(markdownSearch().search("data")).isNull();
    }

    @Test
    public void test_search_is_null_when_the_format_is_absent_from_disk() throws Exception {
        writePages("md", "data");
        completeManifest(1);
        // Same document, same manifest, only the requested format differs, so the null below can
        // come from nothing but the missing format.
        assertThat(markdownSearch().search("data")).isNotNull();
        assertThat(new StructureSearch(reader, dir.getRoot().toPath(), "xhtml").search("data")).isNull();
    }

    @Test
    public void test_search_counts_every_page_in_order_and_omits_the_empty_ones() throws Exception {
        writePages("md", "data and data", "nothing here", "data");
        completeManifest(3);
        StructureSearch.Hits hits = markdownSearch().search("data");
        assertThat(hits.count()).isEqualTo(3);
        assertThat(hits.hits()).hasSize(2);
        assertThat(hits.hits().get(0).page()).isEqualTo(1);
        assertThat(hits.hits().get(0).count()).isEqualTo(2);
        assertThat(hits.hits().get(1).page()).isEqualTo(3);
        assertThat(hits.hits().get(1).count()).isEqualTo(1);
    }

    @Test
    public void test_a_document_with_no_match_answers_zero_rather_than_nothing() throws Exception {
        writePages("md", "# one", "# two");
        completeManifest(2);
        // The distinction the route turns into 200 versus 404: "searched, found none" is not
        // "there is nothing here to search".
        StructureSearch.Hits hits = markdownSearch().search("data");
        assertThat(hits.count()).isEqualTo(0);
        assertThat(hits.hits()).isEmpty();
    }

    @Test
    public void test_a_page_missing_on_disk_holds_no_occurrence_and_the_scan_goes_on() throws Exception {
        Path node = writePages("md", "data", "data", "data");
        // A gap in the middle, not a truncated tail: the manifest still promises page 2, and the
        // scan has to carry on to page 3 rather than stopping or failing at the missing file.
        Files.delete(ArtifactPath.payloadPage(node, ArtifactType.STRUCTURE, 2, "md"));
        completeManifest(3);
        StructureSearch.Hits hits = markdownSearch().search("data");
        assertThat(hits.count()).isEqualTo(2);
        assertThat(hits.hits()).hasSize(2);
        assertThat(hits.hits().get(0).page()).isEqualTo(1);
        assertThat(hits.hits().get(1).page()).isEqualTo(3);
    }

    @Test
    public void test_a_page_is_folded_like_the_elasticsearch_content_search() throws Exception {
        writePages("md", "R\u00e9sum\u00e9 of DATA");
        completeManifest(1);
        // Delegation smoke test only: ContentOccurrencesTest owns the full rule set.
        assertThat(markdownSearch().search("resume").count()).isEqualTo(1);
        assertThat(markdownSearch().search("data").count()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -pl datashare-index -am test -Dtest='StructureSearchTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: **compilation failure**, `cannot find symbol: class StructureSearch`.

- [ ] **Step 3: Write the implementation**

Create `datashare-index/src/main/java/org/icij/datashare/text/artifact/StructureSearch.java`:

```java
package org.icij.datashare.text.artifact;

import org.icij.datashare.text.ContentOccurrences;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * In-document search over one document's persisted structure pages, the read-side counterpart of
 * {@link StructureArtifact}. Built per request rather than shared, because the document's dir and
 * the requested format are fixed for a whole scan and holding them keeps every helper inside the
 * four-parameter limit. Pages are read one at a time, so a document's whole markdown is never
 * resident at once.
 */
public class StructureSearch {
    private static final ArtifactType TYPE = ArtifactType.STRUCTURE;

    private final ArtifactReader reader;
    private final Path docArtifactDir;
    private final String extension;

    public StructureSearch(ArtifactReader reader, Path docArtifactDir, String extension) {
        this.reader = reader;
        this.docArtifactDir = docArtifactDir;
        this.extension = extension;
    }

    /** Per-page occurrence counts in page order, pages with none omitted, or null when this
     *  document has nothing servable to search. Callers map null to 404 the way they map the
     *  reader's own nulls, so "not found" keeps one definition across the artifact routes. */
    public Hits search(String query) throws IOException {
        ManifestEntry entry = searchableEntry();
        if (entry == null) {
            return null;
        }
        return scan(entry, query);
    }

    // The entry only when a servable structure artifact carries this format on disk. Without the
    // formats probe, a document whose page-N.md files are gone would answer "no occurrences"
    // instead of "no markdown here", contradicting what the manifest route told the client.
    private ManifestEntry searchableEntry() throws IOException {
        ManifestEntry entry = reader.servableEntry(docArtifactDir, TYPE);
        if (entry == null || reader.servableTotal(docArtifactDir, TYPE, entry) == null) {
            return null;
        }
        return reader.formats(docArtifactDir, TYPE, entry, List.of(extension)).isEmpty() ? null : entry;
    }

    private Hits scan(ManifestEntry entry, String query) throws IOException {
        List<PageHits> hits = new ArrayList<>();
        int total = reader.servableTotal(docArtifactDir, TYPE, entry);
        for (int page = 1; page <= total; page++) {
            addPage(hits, entry, page, query);
        }
        return new Hits(hits.stream().mapToInt(PageHits::count).sum(), hits);
    }

    private void addPage(List<PageHits> hits, ManifestEntry entry, int page, String query) throws IOException {
        int count = countPage(entry, page, query);
        if (count > 0) {
            hits.add(new PageHits(page, count));
        }
    }

    private int countPage(ManifestEntry entry, int page, String query) throws IOException {
        byte[] payload = reader.page(docArtifactDir, TYPE, entry, page, extension);
        // A page the manifest advertises but disk no longer has: the reader has already logged it
        // and the page route 404s that one page, so here it simply holds no occurrence. Failing a
        // nine-hundred-page search over one missing file would be strictly worse.
        if (payload == null) {
            return 0;
        }
        return ContentOccurrences.count(new String(payload, UTF_8), query);
    }

    /** The response body: the document total, then only the pages carrying an occurrence. */
    public record Hits(int count, List<PageHits> hits) {}

    public record PageHits(int page, int count) {}
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
mvn -pl datashare-index -am test -Dtest='StructureSearchTest,ContentOccurrencesTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0`. Note the **comma** in `-Dtest`; a `+` there matches nothing and still exits 0.

- [ ] **Step 5: Commit**

Use the `/commit` skill with this message:

```
feat(artifacts): count query occurrences across a document's structure pages
```

---

## Task 3: The `search` route

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/ArtifactResource.java:118-150` (the `structurePage` route and the format handling it inlines)
- Test: `datashare-app/src/test/java/org/icij/datashare/web/ArtifactResourceTest.java`

**Interfaces:**
- Consumes: `new StructureSearch(ArtifactReader, Path, String)` and `StructureSearch.Hits search(String)` from Task 2. From existing code in the same class, the private `docArtifactDir(String project, String id, String routing)` returning `Path` or null, the field `reader`, and the constant map `STRUCTURE_CONTENT_TYPES` whose key set is the format whitelist.
- Produces: the HTTP route. Nothing else depends on it.

**Two shapes to preserve.** `requireGranted` runs first on every route in this file, so a non-member gets 403 before learning anything else is wrong with their request; the existing comment at `ArtifactResource.java:134-135` says so. And route methods exceed the four-parameter limit because fluent-http binds path and query segments positionally; every sibling route here already does, so this is the file's established shape rather than a new exception.

- [ ] **Step 1: Write the failing test**

Append these tests to `datashare-app/src/test/java/org/icij/datashare/web/ArtifactResourceTest.java`, after `test_structure_forbidden_for_non_member_project`. They reuse the existing `indexedDocDir`, `writePages`, `writeManifest` and `filesystemManifest` helpers unchanged.

```java
    @Test
    public void test_structure_search_counts_occurrences_across_pages() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, ArtifactType.STRUCTURE, "md", "data and data", "nothing here", "data");
        writeManifest(docDir, filesystemManifest("structure", 3));
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/search?query=data").should()
                .respond(200).haveType("application/json")
                .contain("{\"count\":3,\"hits\":[{\"page\":1,\"count\":2},{\"page\":3,\"count\":1}]}");
    }

    @Test
    public void test_structure_search_route_is_matched_before_the_page_route() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, ArtifactType.STRUCTURE, "md", "data");
        writeManifest(docDir, filesystemManifest("structure", 1));
        // Both routes carry the same segment count, and only fluent-http's UriParser ordering
        // (a literal segment sorts before a :param) puts this one first. Had :page won,
        // parsePageNumber("search") would give 0 and the answer would be 404, so this 200 is the
        // whole assertion.
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/search?query=data").should().respond(200);
    }

    @Test
    public void test_structure_search_answers_zero_for_a_document_with_no_match() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, ArtifactType.STRUCTURE, "md", "# one");
        writeManifest(docDir, filesystemManifest("structure", 1));
        // 200 with an empty result, not 404: the client's counter shows "0 of 0", it does not
        // fall back to plain text.
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/search?query=data").should()
                .respond(200).contain("{\"count\":0,\"hits\":[]}");
    }

    @Test
    public void test_structure_search_rejects_a_blank_query() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, ArtifactType.STRUCTURE, "md", "# one");
        writeManifest(docDir, filesystemManifest("structure", 1));
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/search").should().respond(400);
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/search?query=").should().respond(400);
        // Whitespace-only is not empty, so /documents/searchContent runs it and matches every
        // space on every page. Rejected here instead.
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/search?query=%20%20").should().respond(400);
    }

    @Test
    public void test_structure_search_rejects_an_unsupported_format() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, ArtifactType.STRUCTURE, "md", "# one");
        writeManifest(docDir, filesystemManifest("structure", 1));
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/search?query=one&format=pdf").should()
                .respond(400).contain("md").contain("xhtml");
    }

    @Test
    public void test_structure_search_not_found_without_a_structure_artifact() throws Exception {
        indexedDocDir(DIGEST);
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/search?query=data").should().respond(404);
    }

    @Test
    public void test_structure_search_not_found_for_a_format_absent_on_disk() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, ArtifactType.STRUCTURE, "md", "data");
        writeManifest(docDir, filesystemManifest("structure", 1));
        // Same document and manifest, only the format differs: 404 rather than a zero count, so
        // the client cannot read "no xhtml here" as "your term appears nowhere".
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/search?query=data&format=md").should().respond(200);
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/search?query=data&format=xhtml").should().respond(404);
    }

    @Test
    public void test_structure_search_forbidden_for_non_member_project() {
        get("/api/foo_index/artifacts/structure/" + DIGEST + "/search?query=data").should().respond(403);
        // Membership gates before the query and format checks: a non-member must not learn that
        // their query was blank or their format unsupported before they learn they are not a member.
        get("/api/foo_index/artifacts/structure/" + DIGEST + "/search").should().respond(403);
        get("/api/foo_index/artifacts/structure/" + DIGEST + "/search?query=data&format=pdf").should().respond(403);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
mvn -pl datashare-app -am test -Dtest='ArtifactResourceTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: the eight new tests fail. The two format ones fail with **400 expected, 404 received**, because without the new route `/search` falls through to `structurePage`, where `parsePageNumber("search")` returns 0 and the reader answers null. The rest fail with 404 where 200 or 400 was expected. Show this output before writing production code.

- [ ] **Step 3: Extract the shared format handling**

In `datashare-app/src/main/java/org/icij/datashare/web/ArtifactResource.java`, add these two private statics next to `structureContentTypes()`:

```java
    // The extension a ?format= value asks for, defaulted, or null when it is not one this resource
    // serves. Shared by the page route and the search route so their whitelists cannot drift.
    private static String structureExtension(final String format) {
        String extension = ofNullable(format).filter(value -> !value.isBlank()).orElse(MARKDOWN);
        return STRUCTURE_CONTENT_TYPES.containsKey(extension) ? extension : null;
    }

    private static Payload unsupportedFormat(final String format) {
        return PayloadFormatter.error("unsupported format '" + format + "'; supported formats: "
                + String.join(", ", STRUCTURE_CONTENT_TYPES.keySet()), HttpStatus.BAD_REQUEST);
    }
```

Then replace the body of `structurePage` (currently lines 136-149) with:

```java
        // Membership gates before format validation, same as every other route here: a non-member
        // must see 403 regardless of what else is wrong with the request.
        requireGranted(context, project);
        String extension = structureExtension(format);
        if (extension == null) {
            // A bad format is a request error: 404 on this route means "no such page".
            return unsupportedFormat(format);
        }
        Payload payload = payload(project, id, page, routing, ArtifactType.STRUCTURE, extension,
                STRUCTURE_CONTENT_TYPES.get(extension));
        // The on-disk XHTML is written pre-sanitized by the structure producer; this is the serving
        // side's defense in depth for a payload written by another producer.
        return XHTML.equals(extension)
                ? payload.withHeader("Content-Security-Policy", "default-src 'none'; sandbox")
                : payload;
```

The message is unchanged for a caller: in the failing case the resolved extension equals the raw `format`, so `test_structure_page_rejects_an_unsupported_format` still sees `md` and `xhtml` in the body.

- [ ] **Step 4: Add the route**

In the same file, after `structurePage`, add:

```java
    @Operation(description = "Counts a query's occurrences in a document's structure pages, per page.",
            parameters = {
                    @Parameter(name = "project", description = "the project id", in = ParameterIn.PATH),
                    @Parameter(name = "id", description = "the document id", in = ParameterIn.PATH),
                    @Parameter(name = "query", description = "the term to count, matched literally", in = ParameterIn.QUERY),
                    @Parameter(name = "routing", description = "routing key if not a root document", in = ParameterIn.QUERY),
                    @Parameter(name = "format", description = "md (default) or xhtml", in = ParameterIn.QUERY)
            }
    )
    @ApiResponse(responseCode = "200", description = "JSON {\"count\": N, \"hits\": [{\"page\": P, \"count\": N}]}")
    @ApiResponse(responseCode = "400", description = "if the query is blank, or format is not one of md, xhtml")
    @ApiResponse(responseCode = "403", description = "forbidden if the user doesn't have access to the project")
    @ApiResponse(responseCode = "404", description = "if the document, the artifact, or that format is not found")
    @Get("/:project/artifacts/structure/:id/search?query=:query&routing=:routing&format=:format")
    public Payload structureSearch(final String project, final String id, final String query, final String routing,
                                   final String format, final Context context) throws IOException {
        requireGranted(context, project);
        if (query == null || query.isBlank()) {
            // Not the 404 /documents/searchContent gives an empty query: a missing parameter is a
            // malformed request, and a whitespace query would scan every page to match every space.
            return PayloadFormatter.error("a non-blank query is required", HttpStatus.BAD_REQUEST);
        }
        String extension = structureExtension(format);
        if (extension == null) {
            return unsupportedFormat(format);
        }
        return search(docArtifactDir(project, id, routing), extension, query);
    }
```

and this private helper next to `payload(...)`:

```java
    private Payload search(final Path docArtifactDir, final String extension, final String query) throws IOException {
        if (docArtifactDir == null) {
            return Payload.notFound();
        }
        StructureSearch.Hits hits = new StructureSearch(reader, docArtifactDir, extension).search(query);
        return hits == null ? Payload.notFound() : PayloadFormatter.json(hits);
    }
```

Add the import `org.icij.datashare.text.artifact.StructureSearch` to the project import block, keeping it alphabetical with the neighbouring `org.icij.datashare.text.artifact.*` imports.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
mvn -pl datashare-app -am test -Dtest='ArtifactResourceTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 34, Failures: 0, Errors: 0, Skipped: 0` (26 pre-existing plus the 8 added). All 26 must still pass; the format-helper extraction in Step 3 touches `structurePage`, so `test_structure_page_rejects_an_unsupported_format`, `test_structure_page_serves_xhtml_with_hardening_headers` and `test_structure_forbidden_for_non_member_project` are the ones to read first if anything broke.

- [ ] **Step 6: Run the full module suites**

```bash
mvn -pl datashare-index -am test -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl datashare-app -am test -Dtest='!ExtensionLoaderTest,!TaskManagersIntTest,!BenchDocument,!TaskManagerAmqpTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Run in the background and poll. The four excluded classes fail for environment reasons on any branch, including a pristine base: `ExtensionLoaderTest` because this filesystem reports a non-executable jar as executable, and the three others because `datashare-devenv.properties` points `amqpUri` at 5673 while RabbitMQ listens on 5672. If `TaskResourceTest` fails, re-run it in isolation before blaming this diff; it flakes under full-suite load and passes 69/69 alone.

- [ ] **Step 7: Commit**

Use the `/commit` skill with this message:

```
feat(artifacts): search a document's structure pages over HTTP
```

---

## Verification

Before calling this done, check the diff as a hostile reviewer, hunk by hunk, naming every behavior change including unintended ones. In particular:

1. **`structurePage` changed.** It now resolves the extension through a shared helper. Confirm the 400 body is byte-identical for `?format=pdf` and that the `md` default and the XHTML `Content-Security-Policy` header are untouched.
2. **A new route now shadows a URL that used to 404.** `/artifacts/structure/:id/search` previously fell through to the page route and answered 404. Anything that relied on that (nothing in `datashare-client` does today) now gets 400 or 200.
3. **`ContentOccurrences` is public API in `datashare-index`.** Its quirks are deliberate. Anyone reading it will want to "fix" the decomposed-accent and ligature behavior; the class Javadoc and the tests exist to stop that.

## Follow-ups, not in this diff

- Per-occurrence `offsets` in the response.
- Caching folded pages behind a bounded Guava `CacheBuilder`, keyed by document dir, page, format and the manifest's `taskInput` fingerprint.
- A search route over `page` artifacts.
- The remaining #2306 follow-ups: streaming page payloads, caching the `COMPLETE` manifest, unifying the artifact-dir resolution copies, a route-enumerating membership-gate test, the no-`pagination` reading rule, byte-range staleness.
