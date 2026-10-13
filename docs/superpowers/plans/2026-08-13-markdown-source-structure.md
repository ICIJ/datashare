# Markdown-source structure artifacts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A document whose source is already Markdown produces a structure artifact that reads like that Markdown, with raw HTML sanitized out rather than neutralized by backslash escaping.

**Architecture:** Tika has no Markdown parser, so a `.md` file goes through `TextAndCSVParser` and arrives as one literal text node inside a single `<p>`, which forces flexmark's html2md pass to escape every Markdown metacharacter in it. The fix swaps one DOM for another at a single point in `StructureMarkdownExtractor.extract`: when the type Tika detected is Markdown, the text is parsed as Markdown and rendered to HTML, and the existing pipeline (page selection, jsoup safelist, html2md) runs unchanged on that DOM instead.

**Tech Stack:** Java 17, Maven, Apache Tika 3.3.0, jsoup 1.22.1, flexmark 0.64.8, JUnit 4 with fest-assertions.

**Spec:** `docs/superpowers/specs/2026-08-13-markdown-source-structure-design.md`

## Global Constraints

- Branch: `fix/structure-markdown-source` (already created, based on `main` at 378961456).
- **No new dependency may be downloaded.** `flexmark`, `flexmark-ext-tables` and `flexmark-ext-gfm-strikethrough` are already on the compile classpath transitively via `flexmark-html2md-converter`; the poms only make that explicit. Every version comes from `${flexmark.version}` (0.64.8) in the root pom.
- `com.vladsch.flexmark.parser.Parser` **must be written fully qualified** in `StructureMarkdownExtractor`. The file already imports `org.apache.tika.parser.Parser` (line 12) for `RESILIENT_PARSER`, and importing both is a compile error.
- The branch applies to Markdown content types only. `text/plain` and CSV keep today's escaping, which is correct for them.
- Output must stay a pure function of the input bytes: the content-addressed cache depends on the same bytes yielding byte-identical pages (`StructureMarkdownExtractor.java:37-40`).
- Test command, run from the repo root: `mvn -pl datashare-index -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`. Never drop `-am`. Multiple classes are comma-separated; a `+` silently matches nothing and reports green.
- Commit with the `/commit` skill, not raw `git commit`. Suggested subject lines are given per task.
- No `ponytail:` comments in committed code.

---

### Task 1: Parse a Markdown source as Markdown

**Files:**
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/structure/StructureMarkdownExtractor.java:110-121` (`extract`), `:181-188` (`toXhtml`), and the constants block at `:96-102`
- Modify: `datashare-index/pom.xml:44-47` (after the `flexmark-html2md-converter` entry)
- Modify: `pom.xml:414-418` (after the `flexmark-html2md-converter` `dependencyManagement` entry)
- Test: `datashare-index/src/test/java/org/icij/datashare/text/structure/StructureMarkdownExtractorTest.java`

**Interfaces:**
- Consumes: existing package-private `sanitize(Element)`, `buildMetadata(String, String)`, `baseContentType(Metadata)`, `buildParseContext()`.
- Produces: `String toXhtml(InputStream, Metadata)` (signature change, no external caller: the only call site is `extract`, verified by grep across the repo), `private static boolean isMarkdown(Metadata)`, `private static org.jsoup.nodes.Document parseAsMarkdown(org.jsoup.nodes.Document)`, and the constants `MARKDOWN_OPTIONS`, `MARKDOWN_PARSER`, `MARKDOWN_RENDERER`, `MARKDOWN_TYPES`. Tasks 2 to 4 extend `MARKDOWN_OPTIONS` and `SAFELIST`.

- [ ] **Step 1: Write the failing tests**

Add to `StructureMarkdownExtractorTest`, after `test_page_markdown_derives_from_the_same_sanitized_html` (`:102`). The helper passes both detection hints, exactly as a real indexed `README.md` does.

```java
    // Both hints a real Markdown document carries: the content type Tika detected at INDEX time and
    // the resource name from its metadata.
    private Page markdownPage(String source) throws Exception {
        return extractor.extract(stream(source), "text/x-web-markdown", "README.md").get(0);
    }

    @Test
    public void test_a_markdown_source_is_parsed_as_markdown_not_as_literal_text() throws Exception {
        Page page = markdownPage("# Title\n\nSome **bold** and `code`.\n");

        assertThat(page.markdown()).contains("# Title");
        assertThat(page.markdown()).contains("**bold**");
        assertThat(page.markdown()).contains("`code`");
        // The bug: a Markdown source reaches the converter as one text node, so every metacharacter
        // in it comes back backslash-escaped.
        assertThat(page.markdown()).excludes("\\*");
        assertThat(page.markdown()).excludes("\\`");
        assertThat(page.xhtml()).contains("<h1>Title</h1>");
        assertThat(page.xhtml()).contains("<strong>bold</strong>");
    }

    @Test
    public void test_markdown_lists_and_links_survive_without_escaping() throws Exception {
        Page page = markdownPage("- item one\n- item two\n\nA [link](http://example.com).\n");

        assertThat(page.markdown()).contains("item one");
        assertThat(page.markdown()).contains("[link](http://example.com)");
        assertThat(page.markdown()).excludes("\\[");
        assertThat(page.xhtml()).contains("<li>item one</li>");
    }

    @Test
    public void test_raw_html_in_a_markdown_source_is_sanitized_out_of_both_formats() throws Exception {
        Page page = markdownPage("Real text.\n\n<script>alert(1)</script>\n");

        // Today this survives as "\<script\>alert(1)\</script\>", inert only because of the escaping
        // this change removes, so the safelist has to be what drops it.
        assertThat(page.markdown()).excludes("alert");
        assertThat(page.markdown()).excludes("script");
        assertThat(page.xhtml()).excludes("script");
        assertThat(page.markdown()).contains("Real text.");
    }

    @Test
    public void test_a_markdown_image_and_a_javascript_link_lose_their_targets() throws Exception {
        Page page = markdownPage("![shot](http://remote/x.png)\n\nA [bad](javascript:alert(1)) link.\n");

        assertThat(page.xhtml()).excludes("http://remote/x.png");
        assertThat(page.xhtml()).excludes("javascript:");
        assertThat(page.markdown()).excludes("javascript:");
        assertThat(page.markdown()).contains("bad");
    }

    @Test
    public void test_a_fenced_code_block_keeps_its_content_including_html() throws Exception {
        Page page = markdownPage("```bash\nls -l\n<script>in a fence</script>\n```\n");

        // A code sample is content, not markup: it survives as text on both sides.
        assertThat(page.markdown()).contains("ls -l");
        assertThat(page.markdown()).contains("<script>in a fence</script>");
        assertThat(page.xhtml()).contains("<pre>");
        assertThat(page.xhtml()).contains("&lt;script&gt;in a fence&lt;/script&gt;");
    }

    @Test
    public void test_a_gfm_table_in_a_markdown_source_survives_the_round_trip() throws Exception {
        Page page = markdownPage("| a | b |\n|---|---|\n| 1 | 2 |\n");

        assertThat(page.xhtml()).contains("<table>");
        assertThat(page.markdown()).contains("| a | b |");
        assertThat(page.markdown()).excludes("\\|");
    }

    @Test
    public void test_plain_text_still_escapes_markdown_metacharacters() throws Exception {
        // Not a markup language: a literal "**" in a .txt must stay literal, so the branch above it
        // must not widen to every text/* type.
        Page page = extract(stream("a **b** c"), "text/plain").get(0);

        assertThat(page.markdown()).contains("\\*");
        assertThat(page.xhtml()).excludes("<strong>");
    }

    @Test
    public void test_markdown_extraction_is_deterministic() throws Exception {
        String source = "# Title\n\n- one\n- two\n\n`code`\n";

        assertThat(markdownPage(source).markdown()).isEqualTo(markdownPage(source).markdown());
        assertThat(markdownPage(source).xhtml()).isEqualTo(markdownPage(source).xhtml());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -pl datashare-index -am test -Dtest=StructureMarkdownExtractorTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: 6 failures, 2 passes. The Markdown tests fail on the escaping (`test_a_markdown_source_is_parsed_as_markdown_not_as_literal_text` fails first on `excludes("\\*")`, since the output is `Some \*\*bold\*\* and \`code\``), the raw-HTML test fails because the `.md` contains `alert`, and the table test fails on escaped pipes. `test_plain_text_still_escapes_markdown_metacharacters` and `test_markdown_extraction_is_deterministic` pass already: they pin behaviour this change must not break.

If a Markdown test fails with an `IndexOutOfBoundsException` on `.get(0)` instead, extraction returned no page and the hints are wrong; do not proceed, report it.

- [ ] **Step 3: Declare the two flexmark artifacts explicitly**

In `datashare-index/pom.xml`, directly after the `flexmark-html2md-converter` dependency (`:44-47`):

```xml
        <dependency>
            <!-- Used directly by StructureMarkdownExtractor to parse a Markdown source before the
                 html2md pass; only pulled in transitively today via flexmark-html2md-converter, so
                 declare it explicitly. No version: the root dependencyManagement pin governs it. -->
            <groupId>com.vladsch.flexmark</groupId>
            <artifactId>flexmark</artifactId>
        </dependency>
        <dependency>
            <!-- GFM tables in a Markdown source. Same transitive-but-used case as flexmark above. -->
            <groupId>com.vladsch.flexmark</groupId>
            <artifactId>flexmark-ext-tables</artifactId>
        </dependency>
```

In the root `pom.xml`, directly after the `flexmark-html2md-converter` entry in `dependencyManagement` (`:414-418`):

```xml
            <dependency>
                <groupId>com.vladsch.flexmark</groupId>
                <artifactId>flexmark</artifactId>
                <version>${flexmark.version}</version>
            </dependency>

            <dependency>
                <groupId>com.vladsch.flexmark</groupId>
                <artifactId>flexmark-ext-tables</artifactId>
                <version>${flexmark.version}</version>
            </dependency>
```

- [ ] **Step 4: Add the Markdown parser and the branch**

Two new imports in `StructureMarkdownExtractor.java`, alongside the existing flexmark ones:

```java
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
```

New constants, directly after `MARKDOWN_CONVERTER` (`:96-99`):

```java
    // Tika has no Markdown parser: a Markdown document goes through TextAndCSVParser, which hands back
    // the whole file as a single literal text node, so every "**" reaching the converter above is text
    // and comes out escaped as "\*\*". Parsing that text as Markdown first makes it real elements, which
    // the converter then writes back unescaped. Tables are on because the converter renders them back as
    // GFM tables and would otherwise see a wall of escaped pipes.
    private static final MutableDataSet MARKDOWN_OPTIONS = new MutableDataSet()
            .set(com.vladsch.flexmark.parser.Parser.EXTENSIONS, List.of(TablesExtension.create()));

    private static final com.vladsch.flexmark.parser.Parser MARKDOWN_PARSER =
            com.vladsch.flexmark.parser.Parser.builder(MARKDOWN_OPTIONS).build();

    private static final HtmlRenderer MARKDOWN_RENDERER = HtmlRenderer.builder(MARKDOWN_OPTIONS).build();

    // Tika 3.3.0 detects Markdown as text/x-web-markdown; the other two are the IANA name (RFC 7763)
    // and its legacy alias, so a rename upstream does not silently switch this off.
    private static final Set<String> MARKDOWN_TYPES =
            Set.of("text/x-web-markdown", "text/markdown", "text/x-markdown");
```

Rewrite `extract` (`:110-121`), keeping its javadoc:

```java
    public List<Page> extract(InputStream source, String contentType, String filename)
            throws IOException, SAXException, TikaException {
        // Kept rather than built inside toXhtml: the parse fills it with the type Tika detected.
        Metadata metadata = buildMetadata(contentType, filename);
        org.jsoup.nodes.Document document = Jsoup.parse(toXhtml(source, metadata));
        if (isMarkdown(metadata)) {
            document = parseAsMarkdown(document);
        }
        List<Page> pages = new ArrayList<>();
        for (Element page : selectRootPages(document)) {
            // Both formats come from the same DOM, so they can never disagree about a page's content.
            org.jsoup.nodes.Document sanitized = asXhtmlDocument(sanitize(page));
            String markdown = MARKDOWN_CONVERTER.convert(sanitized).strip();
            pages.add(new Page(serializeAsXhtml(sanitized), markdown));
        }
        return pages;
    }

    // The type Tika settled on rather than the caller's hint: AutoDetectParser writes what it detected
    // into the metadata it is given, so an embedded .md arriving with its container's generic type is
    // recognised here too.
    private static boolean isMarkdown(Metadata metadata) {
        return MARKDOWN_TYPES.contains(baseContentType(metadata));
    }

    // Replaces Tika's single-text-node rendering with a DOM parsed from the Markdown that node holds.
    // Nothing downstream knows the difference: page selection, the safelist and the converter run on it
    // exactly as they run on a parser's own XHTML.
    private static org.jsoup.nodes.Document parseAsMarkdown(org.jsoup.nodes.Document tikaOutput) {
        Element body = tikaOutput.body() != null ? tikaOutput.body() : tikaOutput;
        return Jsoup.parse(MARKDOWN_RENDERER.render(MARKDOWN_PARSER.parse(body.wholeText())));
    }
```

Change `toXhtml` to take the metadata (`:181-188`):

```java
    String toXhtml(InputStream source, Metadata metadata)
            throws IOException, SAXException, TikaException {
        ToXMLContentHandler xhtmlHandler = new ToXMLContentHandler();
        new AutoDetectParser(RESILIENT_PARSER).parse(source,
                new WriteOutContentHandler(xhtmlHandler, maxOutputChars),
                metadata, buildParseContext());
        return xhtmlHandler.toString();
    }
```

Extend the comment above `buildMetadata` (`:190-192`) with one sentence, since the instance now outlives the call:

```java
    // ... the filename is passed as it is. The caller keeps the instance: AutoDetectParser writes the
    // type it detected into it, which is what decides whether the Markdown branch above applies.
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -pl datashare-index -am test -Dtest=StructureMarkdownExtractorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, all tests in the class.

- [ ] **Step 6: Run the artifact tests for regressions**

Run: `mvn -pl datashare-index -am test -Dtest=StructureArtifactTest,ArtifactProducerTest,ArtifactPayloadTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. These use HTML and PDF inputs, which do not take the new branch. A failure here means the branch is firing on a non-Markdown type: check `MARKDOWN_TYPES` against what `baseContentType` returns before changing any expectation.

- [ ] **Step 7: Commit**

Use the `/commit` skill. Suggested subject: `fix(artifacts): parse a Markdown source as Markdown instead of as literal text`.

---

### Task 2: Keep strikethrough as markup

Independent of Task 1's core fix and separately rejectable: it is the one change that widens the safelist for the sake of a GFM construct.

**Files:**
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/structure/StructureMarkdownExtractor.java:58` (`SAFELIST`) and `MARKDOWN_OPTIONS` from Task 1
- Modify: `datashare-index/pom.xml` (the flexmark block from Task 1)
- Modify: `pom.xml` (the `dependencyManagement` block from Task 1)
- Test: `datashare-index/src/test/java/org/icij/datashare/text/structure/StructureMarkdownExtractorTest.java`

**Interfaces:**
- Consumes: `MARKDOWN_OPTIONS`, `markdownPage(String)` from Task 1.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    public void test_strikethrough_in_a_markdown_source_survives_as_markup() throws Exception {
        Page page = markdownPage("~~struck~~ and kept.\n");

        assertThat(page.xhtml()).contains("<del>struck</del>");
        assertThat(page.markdown()).contains("~~struck~~");
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -pl datashare-index -am test -Dtest=StructureMarkdownExtractorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL on the `<del>` assertion. Without the extension the tildes are literal text; and even with it, `Safelist.relaxed()` does not include `del`, so the element is unwrapped to plain `struck`.

- [ ] **Step 3: Enable the extension and allow the tag**

Add the artifact to `datashare-index/pom.xml` next to `flexmark-ext-tables`, and to the root pom's `dependencyManagement` next to it:

```xml
        <dependency>
            <!-- GFM strikethrough in a Markdown source. Same transitive-but-used case. -->
            <groupId>com.vladsch.flexmark</groupId>
            <artifactId>flexmark-ext-gfm-strikethrough</artifactId>
        </dependency>
```

Add the import and extend the parser options:

```java
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
```

```java
    private static final MutableDataSet MARKDOWN_OPTIONS = new MutableDataSet()
            .set(com.vladsch.flexmark.parser.Parser.EXTENSIONS,
                    List.of(TablesExtension.create(), StrikethroughExtension.create()));
```

Add `del` to the safelist (`:58-59`), and extend the comment above it to say why:

```java
    // ... A <del> is added because a Markdown source's "~~struck~~" parses to one and Safelist.relaxed()
    // has no del: it carries no attributes and no active content, and the client's own sanitizer allows it.
    private static final Safelist SAFELIST = Safelist.relaxed().removeTags("u").addTags("del")
            .removeAttributes("img", "src").preserveRelativeLinks(true);
```

- [ ] **Step 4: Run it to verify it passes**

Run: `mvn -pl datashare-index -am test -Dtest=StructureMarkdownExtractorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

**If the `.md` assertion still fails** with `<del>struck</del>` appearing verbatim in the Markdown, the html2md converter is not mapping the tag: add `.set(FlexmarkHtmlConverter.EXT_INLINE_DEL, ExtensionConversion.MARKDOWN)` to `MARKDOWN_CONVERTER` (`:96-99`) and re-run. If that still fails, revert this whole task including the pom entries and the safelist change, and report it: literal `~~` is not worth carrying a converter workaround.

- [ ] **Step 5: Check the golden-bytes tests**

Run: `mvn -pl datashare-index -am test -Dtest=StructureArtifactTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS unchanged. The pinned fixtures contain no `<del>`.

- [ ] **Step 6: Commit**

Use the `/commit` skill. Suggested subject: `fix(artifacts): keep strikethrough as markup in a structure artifact`.

---

### Task 3: Keep a fenced code block's language

The spec makes this verify-or-drop: it is worth one safelist attribute only if the converter actually writes the fence back.

**Files:**
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/structure/StructureMarkdownExtractor.java:58` (`SAFELIST`)
- Test: `datashare-index/src/test/java/org/icij/datashare/text/structure/StructureMarkdownExtractorTest.java`

**Interfaces:**
- Consumes: `markdownPage(String)` from Task 1.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    public void test_a_fenced_code_block_keeps_its_language() throws Exception {
        Page page = markdownPage("```bash\nls -l\n```\n");

        assertThat(page.xhtml()).contains("class=\"language-bash\"");
        assertThat(page.markdown()).contains("```bash");
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -pl datashare-index -am test -Dtest=StructureMarkdownExtractorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL on the `class="language-bash"` assertion. The renderer emits `<pre><code class="language-bash">`, and `sanitize()` strips the class, after which the converter falls back to a 4-space indented block.

- [ ] **Step 3: Allow class on code only**

```java
    // ... "class" on a <code> only, so a fenced block keeps the language the converter needs to write
    // the fence back. A class name is inert without an attacker-supplied stylesheet, and jsoup cannot
    // filter attribute values, so this admits any class name on that one tag.
    private static final Safelist SAFELIST = Safelist.relaxed().removeTags("u").addTags("del")
            .addAttributes("code", "class")
            .removeAttributes("img", "src").preserveRelativeLinks(true);
```

(If Task 2 was reverted, leave out `.addTags("del")`.)

- [ ] **Step 4: Run it to verify it passes**

Run: `mvn -pl datashare-index -am test -Dtest=StructureMarkdownExtractorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

**If the `.md` assertion still fails** while the `.xhtml` one passes, the converter does not turn a preserved class into a fence. **Drop this task**: revert the safelist change, delete this test, and report that fenced blocks degrade to indented blocks. Do not add converter options to force it.

- [ ] **Step 5: Check the golden-bytes tests**

Run: `mvn -pl datashare-index -am test -Dtest=StructureArtifactTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS unchanged. The pinned fixtures contain no `<code class=...>`.

- [ ] **Step 6: Commit**

Use the `/commit` skill. Suggested subject: `fix(artifacts): keep a fenced code block's language in a structure artifact`.

---

### Task 4: Pin the unordered list marker to a dash

Cosmetic and the only task that changes output for **every** document type, so it is last and separately rejectable.

**Files:**
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/structure/StructureMarkdownExtractor.java:96-99` (`MARKDOWN_CONVERTER`)
- Modify: `datashare-index/src/test/java/org/icij/datashare/text/artifact/StructureArtifactTest.java:115` (pinned expectation)
- Test: `datashare-index/src/test/java/org/icij/datashare/text/structure/StructureMarkdownExtractorTest.java`

**Interfaces:**
- Consumes: `markdownPage(String)` from Task 1.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    public void test_unordered_lists_keep_the_dash_marker_a_markdown_source_used() throws Exception {
        Page page = markdownPage("- one\n- two\n");

        assertThat(page.markdown()).contains("- one");
        assertThat(page.markdown()).excludes("* one");
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -pl datashare-index -am test -Dtest=StructureMarkdownExtractorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL. The converter normalizes every unordered list to `*`, so the output is `* one`.

- [ ] **Step 3: Set the delimiter**

```java
    private static final FlexmarkHtmlConverter MARKDOWN_CONVERTER = FlexmarkHtmlConverter.builder(
            new MutableDataSet()
                    .set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false)
                    .set(FlexmarkHtmlConverter.UNORDERED_LIST_DELIMITER, '-')
                    .set(FlexmarkHtmlConverter.MAX_BLANK_LINES, 1)).build();
```

- [ ] **Step 4: Run it to verify it passes**

Run: `mvn -pl datashare-index -am test -Dtest=StructureMarkdownExtractorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Update the one golden-bytes expectation this changes**

`StructureArtifactTest.test_the_stored_page_set_is_pinned` (`:102`) pins `"* one\n* two"` at `:115` for an HTML input. That test's own comment says to confirm the change was intended and then update the expectation, which is what this is:

```java
        assertThat(readPage(1, "md")).isEqualTo("# Title\n\nplain **bold** and *it* and under\n\n"
                + "- one\n- two\n\n[next](page2.html)\n\nclean");
```

Run: `mvn -pl datashare-index -am test -Dtest=StructureArtifactTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. If any **other** expectation in that class also moved, stop and report: only the list marker should have changed.

- [ ] **Step 6: Commit**

Use the `/commit` skill. Suggested subject: `refactor(artifacts): write unordered lists with the dash marker`.

---

## Final verification

- [ ] Run the whole module: `mvn -pl datashare-index -am test`. Expected: PASS. Compare any failure against `main`; four classes in this repo fail for environment reasons on any branch, so a failure is only yours if it does not reproduce on `main`.
- [ ] Rebuild and re-index a `.md` document locally, then fetch `/api/local-datashare/artifacts/structure/<id>/1` and confirm the backslashes are gone and `?format=xhtml` shows real headings and lists rather than one `<p>`.
