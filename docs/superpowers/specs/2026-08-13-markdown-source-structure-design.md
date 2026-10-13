# Design: structure artifacts for documents whose source is already Markdown

Reported 2026-08-13 against `main` at 378961456 (release 21.18.0), from a live local instance:
`GET /api/local-datashare/artifacts/structure/<id>/1` on an indexed `README.md` returns Markdown
littered with backslashes (`\*\*Embedding endpoint\*\*`, `` \`/api/embedding\` ``). Part of
epic(artifacts) #2229. No issue filed yet.

Consumer: the datashare-client Text tab, which fetches this route
(`datashare-client/src/api/index.js:407`) and renders the response as Markdown
(`datashare-client/src/utils/markdown.js:67`).

## Goal

A document whose source *is* Markdown should produce a structure artifact that reads like that
Markdown, not like Markdown escaped into inert text, and the escaping it loses must be replaced by
real sanitization rather than by nothing.

## Current state

`StructureMarkdownExtractor.extract` (`StructureMarkdownExtractor.java:110`) runs one pipeline for
every document: Tika XHTML, split on `div.page`, per-page `sanitize()` against a jsoup `Safelist`,
then flexmark's html2md converter for the `.md` format and jsoup XML serialization for the
`.xhtml` format. Both formats come off the same DOM by construction (`:115`).

**Tika does not parse Markdown as Markdown.** The reported document is detected as
`text/x-web-markdown` and parsed by `org.apache.tika.parser.csv.TextAndCSVParser` (both read from
the indexed document's `tika_metadata_*` fields in Elasticsearch). That parser emits the whole file
as **one literal text node inside a single `<p>`**. Confirmed against the live instance with
`?format=xhtml`, which returns the source verbatim, HTML-escaped, inside one paragraph. Nothing in
the document is structure; everything is text. The root pom already documents this exact failure
shape for a different input (`pom.xml:420`): when a jsoup downgrade knocked out Tika's `JSoupParser`,
`text/html` fell back to `TextAndCSVParser`, "which HTML-escapes the whole document instead of
parsing its structure".

**The escaping is a symptom, and it is also the only thing keeping the `.md` safe.** Because the
whole file is one text node, flexmark must escape every Markdown metacharacter in it, including
`<`. A probe over the pipeline on `main` (throwaway, not committed) returned:

```
Some \*\*bold\*\* and \`code\`.
\<script\>alert(1)\</script\>
\<img src="http://evil/x" onerror="alert(2)"\>
```

`\<script\>` renders as literal text under CommonMark, so today's `.md` is inert **because of** the
backslashes. Any change that simply removes them, or that serves the source bytes verbatim, would
turn raw `<script>` in a Markdown source into live HTML for any consumer that renders raw HTML.
The known consumer does not (`datashare-client/src/utils/markdown.js:49` notes `remark-rehype`
defaults to `allowDangerousHtml:false`, and `rehypeSanitize` runs after it at `:56`), but the route
is documented public API, so the payload cannot rely on one consumer's renderer settings for its
safety.

## Design

**Parse Markdown as Markdown, then leave the existing pipeline untouched.**

In `extract`, build the `Metadata` up front and hand it to `toXhtml` instead of letting `toXhtml`
build its own. After the parse, that `Metadata` holds the type Tika **actually detected**. If that
type is Markdown, discard Tika's `<p>` blob and rebuild the DOM by parsing the body text with
flexmark's Markdown parser and rendering it to HTML:

```java
Metadata metadata = buildMetadata(contentType, filename);
String xhtml = toXhtml(source, metadata);
org.jsoup.nodes.Document document = isMarkdown(metadata)
        ? Jsoup.parse(MARKDOWN_RENDERER.render(MARKDOWN_PARSER.parse(bodyText(xhtml))))
        : Jsoup.parse(xhtml);
```

where `bodyText` is `Jsoup.parse(xhtml).body().wholeText()`. Everything downstream (page selection,
`sanitize()`, html2md, XHTML serialization) is unchanged, so the change is a swap of one DOM for
another at a single point.

The escaping then disappears on its own: `**bold**` reaches flexmark as a `<strong>` **element**
rather than as literal text, so html2md emits `**bold**` back with no backslash. Same for headings,
lists, links, tables and code.

### Why detection reads the parse metadata, not the caller's hint

`AutoDetectParser` writes the type it detected into the `Metadata` it is given. Probed with a
deliberately useless hint (`application/octet-stream`) and the filename `README.md`, the metadata
came back `text/x-web-markdown; charset=ISO-8859-1`. Reading it there means an embedded `.md`
attachment, which reaches `StructureArtifact` with its container's generic type, still routes
correctly. Parameters are stripped with the existing `baseContentType` helper
(`StructureMarkdownExtractor.java:241`).

Matched types: `text/x-web-markdown` (what Tika 3.3.0 detects, verified), plus `text/markdown`
(RFC 7763) and `text/x-markdown` (legacy alias). The aliases cost one `Set.of` entry each and keep
the branch working if Tika renames its type.

### Scope of the branch

Markdown types only. `text/plain`, CSV and source code keep today's behaviour, where escaping is
**correct**: a literal `*` in a `.txt` must stay a literal `*`. This is not a general fix for
"plain-text-shaped documents"; it applies only where the plain text is a markup language we can
parse.

### Sanitization

Unchanged mechanism, applied to a DOM that now has real elements: the existing `SAFELIST`
(`StructureMarkdownExtractor.java:58`) is what drops raw HTML from the Markdown source. Probed
end to end on the pipeline this design proposes:

| Source construct | After sanitize | In the `.md` |
|---|---|---|
| `<script>alert(1)</script>` | dropped | absent |
| `<img src="http://evil/x" onerror="alert(2)">` | `<img>`, no attributes | absent |
| `[bad](javascript:alert(3))` | anchor dropped, text kept | `bad` |
| `<b>bold</b>` | kept (safelisted) | `**bold**` |
| ` ```bash ` fence containing `<script>` | `<pre><code>`, script as text | code block, script as text |

So code samples survive as code, and active content does not. The `.xhtml` route keeps its
`Content-Security-Policy: default-src 'none'; sandbox` header (`ArtifactResource.java:148`).

### Three fidelity decisions the probe forced

Each is a real loss against the source that the probe showed, with the fix and its cost.

1. **GFM tables.** `flexmark-ext-tables` is already on the compile classpath transitively, and the
   probe confirmed a table survives the full round trip as a GFM table. Enable `TablesExtension` on
   the parser. Without it a table degrades to escaped pipe text.
2. **Strikethrough.** `~~struck~~` parses to `<del>`, which `Safelist.relaxed()` does **not**
   include: the probe returned plain `struck`, losing the markup. Enabling the extension is only
   worth it together with `SAFELIST.addTags("del")`. Do both, since the client's sanitizer allows
   `del` (it clones rehype-sanitize's GitHub schema, `markdown.js:17`) and `<del>` carries no
   attributes and no active content. The alternative, leaving both out, means `~~` survives as
   escaped literal text, which is the bug this design exists to remove.
3. **Fenced code loses its language.** `<pre><code class="language-bash">` reaches `sanitize()`,
   which strips `class`, and html2md then emits a 4-space indented block instead of a fence: the
   `bash` tag is gone and the fence style changes. Fix by allowing `class` on `code` only
   (`SAFELIST.addAttributes("code", "class")`). A class value is inert without an attacker-supplied
   stylesheet, and jsoup cannot filter attribute values, so this admits arbitrary class names on
   `code` for every document type. **Implementation must verify** that html2md emits a
   ```` ```bash ```` fence from the preserved class; if it does not, drop this decision and accept
   indented code blocks rather than add machinery for it.

   **Verified and kept, but the reasoning above was wrong about the risk.** A class value is not
   merely inert markup: html2md writes it verbatim into the fence info string, and the `.md` is never
   sanitized after conversion, so a value carrying a newline and a closing fence ends the fence early
   and drops the rest of itself into the payload as a raw HTML block. jsoup cannot filter attribute
   values, so the safelist alone cannot close that. `sanitize()` therefore normalizes `code[class]` in
   its pre-pass, keeping one token matching `language-[\w.+#-]+` and dropping the attribute otherwise.
   That also fixes a real-world nuisance (`class="hljs sourceCode python"` produced a three-token
   garbage info string) and keeps the two formats agreeing, since a raw newline in an XML attribute is
   collapsed on read in the `.xhtml` but not in the `.md`.

Decisions 2 and 3 widen `SAFELIST`, which is shared with every other document type. That is
intended: both are formatting-only, and a safelist that differs per source type would be two
security rules to keep in step.

### Known consequences

- **The `.md` is not byte-identical to the source.** flexmark normalizes: unordered list markers
  become `*` (pin to `-` with `FlexmarkHtmlConverter.UNORDERED_LIST_DELIMITER` to match the
  prevailing convention in real READMEs), blank-line runs collapse to one (`MAX_BLANK_LINES`, `:99`,
  already true today), and fence style may change per decision 3. It reads as the same document,
  not as the same bytes. Anyone needing the exact bytes has `/artifacts/raw/:id`.
- **Images disappear from a Markdown document's `.md` entirely.** `![alt](http://host/x.png)` renders
  to `<img>`, whose `src` the safelist strips as a tracking beacon
  (`StructureMarkdownExtractor.java:56`), and html2md emits nothing at all for a src-less `<img>`, so
  the alt text survives only in the `.xhtml`. A badge row or screenshot becomes a gap. This is a
  regression against `main` for Markdown sources, where the image at least remained readable as
  escaped text, and it is the rule already applied to every HTML document. Accepted: the client
  blocks `img` outright (`markdown.js:12`), so no consumer renders it today either way.
- **A thematic break needed two changes to survive.** `---` parses to `<hr>`, which
  `Safelist.relaxed()` drops, so the break vanished silently; `hr` joins `del` on the safelist for the
  same reason (formatting-only, no attributes, no active content). flexmark then writes a break as
  `*** ** * ** ***` by default, so `THEMATIC_BREAK` is pinned to `---`. Like the list-marker pin, that
  applies to every document type, not only Markdown sources.
- **Raw `<div class="page">` inside a Markdown source now splits pages**, because page selection
  runs before sanitization. No content is lost (`appendStraysToLastPage`, `:158`) and no realistic
  Markdown document does this.
- **Every Markdown document's artifact goes stale.** No `taskInput` change is needed:
  `BuildVersions.DATASHARE` already invalidates every structure artifact on each release
  (`StructureArtifact.java:48`).
- Determinism is preserved: flexmark parsing and rendering are pure functions of the text, so the
  same bytes still yield byte-identical output, which the content-addressed cache relies on.

## Files

- `datashare-index/src/main/java/org/icij/datashare/text/structure/StructureMarkdownExtractor.java`:
  the branch, a Markdown parser/renderer pair built once as static finals next to the existing
  `MARKDOWN_CONVERTER`, `isMarkdown`, and the `toXhtml` signature change.
- `datashare-index/pom.xml`: declare `flexmark` and `flexmark-ext-tables` explicitly, following the
  convention the jsoup entry already sets (`pom.xml:48`). Both are on the compile classpath today
  via `flexmark-html2md-converter`, so **no new dependency is added**; add matching
  `dependencyManagement` entries under `${flexmark.version}` in the root pom.
- `datashare-index/src/test/java/org/icij/datashare/text/structure/StructureMarkdownExtractorTest.java`:
  the tests below.

## Testing

TDD, RED before any production code. Each test drives a real Markdown source through `extract`.

1. `**bold**` survives unescaped in the `.md` and appears as `<strong>` in the `.xhtml`. RED today:
   the `.md` contains `\*\*`.
2. Headings, lists and inline code round-trip without backslashes.
3. `<script>alert(1)</script>` in a Markdown source is absent from **both** formats. RED today: the
   `.md` carries it, escaped.
4. `<img src="http://remote/x" onerror=...>` yields no `src` and no handler; `[x](javascript:...)`
   yields no anchor.
5. A fenced code block keeps its content, and the `<script>` inside it survives as text.
6. A GFM table round-trips as a table.
7. `text/plain` containing `*` still escapes it, pinning that the branch is narrow.
8. Two extractions of the same Markdown bytes are byte-identical (extends the existing
   `test_extract_is_deterministic`).

Existing tests must stay green unchanged; the safelist additions from decisions 2 and 3 are the
only edits that can touch non-Markdown expectations.

## Out of scope

- Other markup-in-plain-text formats (reStructuredText, AsciiDoc, Org). Same shape, no report, no
  demand.
- Any client change. The client already renders and sanitizes this route.
- Changing what `/artifacts/raw` serves.
