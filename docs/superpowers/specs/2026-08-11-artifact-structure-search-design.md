# Design: in-document search over structure artifacts

Follow-up to PR #2306 (`feat/artifacts-resource`), **merged 2026-08-11 12:34 UTC**. Part of
epic(artifacts) #2229. No issue filed yet.

Consumer: the datashare-client Text tab, whose approved design is
`datashare-client/docs/superpowers/specs/2026-08-10-markdown-text-tab-design.md`. That spec names
this endpoint a prerequisite and ships the client once, markdown-native, against it.

## Goal

One route that counts a query's occurrences in a document's persisted markdown structure pages, so
the client's in-document search describes **what the user actually sees**. The client renders the
markdown artifact; the raw extracted text is a different string, and offsets into it do not map
onto the markdown.

## Current state

**`main` carries the whole #2306 stack.** `ArtifactResource` (247 lines) serves
`/:project/artifacts/{page,structure,raw}/:id[/:page]`; `ArtifactReader` owns every manifest and
on-disk-layout rule; `StructureArtifact` writes `page-N.xhtml` and `page-N.md` per page.

**Matching semantics of the endpoint we align with.** `/documents/searchContent`
(`DocumentResource.java:230`) delegates to `ElasticsearchIndexer.searchContentOccurrences`
(`ElasticsearchIndexer.java:404`), which runs `searchOccurrences.painless.java` as an ES script
field. Read from that script:

- `getOffsets` (lines 41-52): literal substring via `indexOf`, never a regex, advancing by
  `query.length()` (line 45, 49), so matches are **non-overlapping**.
- Both sides are folded by `normalizeLetters(x.toLowerCase())` (lines 43-44). `toLowerCase()` takes
  no `Locale`, so it uses the ES node's default.
- `normalizeLetters` (lines 28-39) is per-char and **conditional**: a char is NFKD-normalized and
  stripped of `NON_SPACING_MARK` only when `Character.getType(c) == Character.LOWERCASE_LETTER`.
  Everything else passes through untouched (lines 34-35). Three consequences:
  - precomposed `é` (U+00E9) folds to `e`, but **decomposed** `e` + U+0301 does not, because the
    combining acute is `NON_SPACING_MARK`, not `LOWERCASE_LETTER`;
  - caseless scripts (Arabic, Hebrew, CJK are `OTHER_LETTER`) are never normalized at all;
  - NFKD is *compatibility*, so `ﬁ` expands to `fi` and the folded string outgrows the source. The
    offsets the script returns index the **folded** string, so they already drift past such a char.
- Blank query: `query.isEmpty()` throws at `ElasticsearchIndexer.java:406`, which
  `DocumentResource.java:248` maps to **404**, not 400. Whitespace-only is not empty, so `"  "`
  runs and matches every space.

**The bytes a scan reads are bounded; the number of pages it walks is not.**
`StructureMarkdownExtractor.java:74` caps Tika's output at `DEFAULT_MAX_OUTPUT_CHARS = 16_000_000`,
applied at line 185 through `WriteOutContentHandler`. The markdown is derived from that XHTML, so
the *content* a datashare-produced artifact can hold is under 16M chars by construction.

That cap does **not** bound the `total` a `manifest.json` advertises. `ArtifactReader.servableTotal`
accepts any `total > 0` (`ArtifactReader.java:51`), and the scan loop runs `total` times regardless
of how many files exist. This route is the first to do O(total) work: `structureManifest` only
prints the number and `structurePage` does a single stat. `ArtifactReader`'s own javadoc names
foreign producers ("including datashare-python") as the threat model behind its null-not-500
discipline, and a producer bug writing a garbage `total` would cost one request thread that many
filesystem stats rather than one. Real corpora are unaffected (a 900-page PDF is 900 stats), which
is why Decision 8 still declines a cap, but the reason is "the realistic ceiling is small", not
"a cap already exists".

**Route precedence is decided by fluent-http's sorter, not by declaration order.**
`/:project/artifacts/structure/:id/search` and the existing `/:project/artifacts/structure/:id/:page`
(`ArtifactResource.java:131`) have the same segment count. `RouteSorter` sorts by
`UriParser.compareTo`, first on param count, then, on a tie, on the first position where one pattern
holds a literal and the other a `:param`, putting the literal first. Read from the bytecode of
`fluent-http-5ee80de.jar` (`datashare-app/pom.xml:31-32`). The literal `search` wins either way,
but nothing in the source says so, hence the route-precedence test under Testing.

**`ManifestRepository` is never Guice-bound**, by convention: `ArtifactTask`, `IndexTask`,
`ArtifactCoverageChecker` and `ArtifactResource` all hand-wire `new FilesystemManifestRepository()`.
The new collaborator follows that, not a new binding.

## Contract

```
GET /api/:project/artifacts/structure/:id/search?query=:q&routing=:routing&format=:format

200 { "count": 12, "hits": [ { "page": 3, "count": 2 }, { "page": 7, "count": 10 } ] }
400 blank query, or a format outside { md, xhtml }
403 not a project member
404 no servable structure artifact, or that format is not on disk
```

`hits` is in page order, a page with no occurrence is omitted, and `count` is the sum over `hits`.

"Blank query" means `query == null || query.isBlank()`. Otherwise the query is used **verbatim and
never trimmed**: a trailing space is a meaningful term, and `searchContent` does not trim either.

`format` absent, null or blank means `md`, matching the page route's
`ofNullable(format).filter(value -> !value.isBlank()).orElse(MARKDOWN)`
(`ArtifactResource.java:137`); any other value outside `{ md, xhtml }` is a 400 carrying the same
message the page route gives.

## Decisions

1. **Branch off `main`.** #2306 merged, so there is nothing to stack on.

2. **Per-page counts, no offsets.** The client needs the total for its counter and per-page
   granularity for navigation ("active mark = nth match in the page"); it marks the rendered DOM by
   text-node walk, not by offset. Dropping offsets bounds the response by page count alone (~20KB
   for a 1000-page document) so no cap, no truncation flag, and no partial-array contract is
   needed, and it removes the only reason the fold would have to be length-preserving. Adding
   `offsets` later is purely additive. Precise offset marking needs remark positions client-side
   anyway, which that spec already lists as its own follow-up.

3. **Port the Painless fold verbatim, into `ContentOccurrences`.** New class in
   `datashare-index/.../text/`, the same module that ships `searchOccurrences.painless.java` as a
   resource, with a javadoc naming that script as its twin. Two statics, no state:
   - `fold(String)` mirrors `normalizeLetters(input.toLowerCase())` exactly, including the three
     consequences listed under Current state. They are not bugs to fix here: parity with
     `searchContent` is the requirement, and the client keeps counting global terms through
     `searchDocument` in the same UI.
   - `count(String content, String query)` mirrors `getOffsets`, advancing by the **raw**
     `query.length()` as the script does, so a ligature-bearing query overlaps exactly as it does in
     ES today. It guards `query.isEmpty()` returning 0, because the script's loop would spin forever
     on it and a library method that hangs on `""` is a landmine regardless of who calls it.

4. **One added fast path in the fold: `c < 0x80` appends directly.** Provably identical, since ASCII
   lowercase letters are NFKD-stable and carry no marks, and it skips the `Normalizer` call on
   nearly every char of Latin text. Known ceiling, to be named in a plain comment at the fold: a
   large **Cyrillic or Greek** document still pays a `Normalizer` call per letter, because those
   scripts are `LOWERCASE_LETTER`. The per-page *content* is bounded by the 16M-char producer cap,
   so the folding cost per real document cannot run away; see the Current state note on what that
   cap does and does not bound.

5. **Two knowing divergences from the script, both narrow.** `Locale.ROOT` on `toLowerCase` instead
   of the ES node's default locale, differing only under a Turkish, Azeri or Lithuanian default
   (Java's conditional special-casing covers `lt` alongside `tr` and `az`: under `lt`, `"İ"`
   lowercases to `i` + U+0307 + U+0307 rather than `i` + U+0307), and following
   the choice the codebase already made explicitly at `ArtifactPath.java:53-55`. And 400 on a blank
   query where `searchContent` gives 404 on an empty one: 400 is the correct code for a malformed
   request, the client spec asks for it, and rejecting whitespace-only additionally stops a
   space-matches-everything scan of every page.

6. **`StructureSearch` owns the page loop.** New class in `datashare-index/.../text/artifact/`,
   wrapping `ArtifactReader`, hand-wired per the convention above. One method,
   `search(Path docArtifactDir, String query, String extension)`, returning a
   `Hits(int count, List<PageHits> hits)` record Jackson serializes straight into the response, or
   `null` for "nothing servable here", matching the reader's own null-means-404 convention. It holds
   one page at a time.

7. **The format check moves to a shared helper.** `ArtifactResource` currently inlines resolve-and-
   validate at lines 137-143. The search route needs the same whitelist and the same 400, so it
   becomes one private helper both routes call, and the two cannot drift.

8. **No cache in v1.** Per request: one ES GET, one `manifest.json` parse, N `Files.readAllBytes`,
   N folds. This *lowers* ES load, because `searchContent` today pulls the full document content
   into a script field inside ES on every throttled keystroke (`ElasticsearchIndexer.java:409-411`)
   while this route excludes `content` and `content_translated` from its GET
   (`ArtifactResource.java:177`) and scans local files instead. A typical 20-page document is ~40KB.
   Measure on a real corpus before adding anything. Upgrade path if it is needed: a bounded Guava
   `CacheBuilder`, already on the classpath through Guice, keyed by document dir + page + format +
   the manifest's `taskInput` fingerprint so a re-extraction invalidates it.

9. **`reader.formats(...)` decides the format 404.** Without it, a document whose `.md` files are
   absent returns `200 {"count": 0}` and reads to the client as "no matches" rather than "no
   markdown here". It reuses the exact predicate the manifest route advertises `formats` with
   (`ArtifactResource.java:213`), so the two routes cannot tell the client different stories.

10. **A page the manifest advertises but disk lacks contributes zero and the scan continues.**
    `reader.page` returns null and has already logged it (`ArtifactReader.java:77`), and the page
    route 404s exactly that one page, so "no occurrences here" is the consistent read. Failing a
    900-page search because one file went missing would be strictly worse than a count short by
    whatever was on it.

11. **The count describes markdown source, and that is deliberate.** A search for `bold` matches
    inside `**bold**`; `very bold` across a marker does not. The client spec already accepts this.
    Stated here so nobody later "fixes" it by stripping markup before counting, which would break
    the offsets Decision 2 leaves the door open for. Searching `?format=xhtml` matches tag names for
    the same reason; it is supported because the whitelist is shared, not because anyone needs it.

12. **Structure only.** No search route for `page` artifacts: `searchContent` already covers that
    content, over the same text.

## Data flow

`requireGranted(context, project)` first, always, per the existing comment at
`ArtifactResource.java:134-135`: a non-member sees 403 regardless of what else is wrong with the
request. Then blank query → 400, then format → 400, then `docArtifactDir(...)`, the existing private
that resolves the document through the indexer so a URL cannot probe arbitrary digests or another
project's data, then `StructureSearch.search`.

Inside `search`: `servableEntry` null → `null`; `servableTotal` null → `null`;
`reader.formats(dir, STRUCTURE, entry, List.of(extension))` empty → `null`. Then pages 1..total
through `reader.page(...)`, decoded UTF-8 to match `StructureArtifact.java:151-154`, counted through
`ContentOccurrences`, accumulated.

## Error handling

Nearly all of it is already the reader's, and this design adds no second opinion.

- Malformed `manifest.json`: `servableEntry` catches `JsonProcessingException` and returns null
  (`ArtifactReader.java:35-38`) → 404.
- `artifactDir` unset: `docArtifactDir` warns once and returns null
  (`ArtifactResource.java:181-188`) → 404.
- Missing page file: Decision 10.
- Malformed UTF-8 in a page: `new String(bytes, UTF_8)` substitutes U+FFFD rather than throwing,
  which is what a scan wants.
- `IOException` from disk propagates to a 500, as the page route's does; it declares
  `throws IOException` too.

## Testing

**`ContentOccurrencesTest`** (datashare-index, no filesystem, no ES) pins the **quirks**, because
the quirks are the parity contract and a well-meant cleanup is the realistic threat: case
insensitivity; precomposed `é` folding to `e`; decomposed `e` + U+0301 **not** folding; a caseless
script passing through untouched; `"aaaa"` against `"aa"` counting 2, not 3; empty query counting 0.

**`StructureSearchTest`** (datashare-index, `TemporaryFolder` against real files, following the
convention `ArtifactResourceTest` set deliberately): no entry; non-`COMPLETE` entry; unusable page
total; format absent on disk; a removed middle page; page ordering; zero-hit pages omitted; total
equals the sum.

**`ArtifactResourceTest`** adds the HTTP shell: the JSON shape on 200; 400 on blank and on
whitespace-only; 400 on an unsupported format; 404 with no artifact; 404 with the `.md` files
absent; and 403 for a non-member **asserted with a missing query too**, so a future reorder cannot
leak a 400 to someone who should see 403.

Plus one test defending a fact that only bytecode states: **route precedence**. A 200 on
`.../search?query=x` proves the literal segment beat `:page`, because had `:page` won,
`parsePageNumber("search")` returns 0 (`ArtifactResource.java:238-245`) and the answer would be 404.
The test name will say so.

## Out of scope

- Per-occurrence `offsets` (Decision 2; additive when wanted).
- Caching folded pages (Decision 8; upgrade path recorded there).
- A search route over `page` artifacts (Decision 12).
- Anything from the #2306 follow-up list not named here: streaming page payloads instead of
  buffering a `byte[]`, caching the immutable `COMPLETE` manifest, unifying the remaining copies of
  artifact-dir resolution, the route-enumerating membership-gate test, the no-`pagination` reading
  rule, and byte-range staleness.

## What changes

| File | Change |
| --- | --- |
| `datashare-index/.../text/ContentOccurrences.java` | new, ~25 lines |
| `datashare-index/.../text/artifact/StructureSearch.java` | new |
| `datashare-app/.../web/ArtifactResource.java` | one route, one extracted format helper |
| `datashare-index/.../text/ContentOccurrencesTest.java` | new |
| `datashare-index/.../text/artifact/StructureSearchTest.java` | new |
| `datashare-app/.../web/ArtifactResourceTest.java` | route tests |

No new dependency, no Guice binding change, no `ArtifactReader` change.
