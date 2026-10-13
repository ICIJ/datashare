# Design: `structure` artifact (Tika XHTML + Markdown) (#2226)

Part of epic(artifacts) #2229. Follows the merged foundation (#2223 type abstraction +
registry, #2224 manifest.json, PR #2231). Productionizes the PoC branch
`poc/tika-document-structure-extraction` (22 commits, branched at `00f475cca`, extract 9.6.0).

Normative reference: the Document artifacts storage convention, at
`technology/services/data-engineering-ml/document-artifacts-convention.md` in the ICIJ docs repo.
Section marks below (§NN) are line numbers in that file. This design follows it literally, which
makes datashare (Java) the first conformant implementation.

## Goal

Produce a per-page structured rendering of every document under `artifactDir`: one sanitized
XHTML file and one Markdown file per page, indexed by the document's `manifest.json`, from the
ARTIFACT stage. Produce-only: serving is #2228.

## Current state

**On main.** The foundation is in place and `RawArtifact` is the only wired producer.
`ArtifactType.STRUCTURE` is already declared (`datashare-api/.../ArtifactType.java:14`) but
nothing in Java produces it. The uniform loop already exists in
`ArtifactProducer` (skip-if-current, produce, stamp terminal, record under the per-document
manifest lock, isolate failures per type per document) and needs no change for a second producer.
The catalog is built at `ArtifactTask.java:136`.

**In the PoC.** `StructureMarkdownExtractor` (172 lines) plus its 165-line test are sound and
carry over. Everything else in that branch is superseded: `ArtifactPath.structure*` helpers keyed
on a `.complete` marker, `ArtifactTask.writeStructure`, and the `DocumentResource` endpoints.
The PoC wrote a single whole-document `structure.xhtml` plus per-page `.md`; this design writes
both formats per page.

**Cross-repo.** `datashare-python` main (`5a1c095`) already ships a `structure` producer
(docling), and it does not follow the convention doc:

- `StructureArtifact.filename = "structure"` (`workers/extract-worker/extract_worker/objects.py:44`)
  writes a **file** at `<id>/structure`, where the convention makes `structure/` a **directory**.
- Its pagination serializes flat inside `pages`
  (`{"total": 2, "byteRanges": [[0,10],[10,20]], "type": "byteRanges"}`, verified by dumping the
  models in that repo's venv), where the convention nests `pages.pagination` and names the
  offsets `ranges`.
- `datashare_python/utils.py:357` still carries `# TODO: WARNING many writers could write at the
  time`, so the convention's concurrency MUST (§138) is unmet there.

Converging Python is out of scope here (see Follow-ups).

## Decisions

1. **Two producers, one manifest key.** The Java Tika producer is the built-in path for
   deployments without the Python/Temporal/docling stack. Both write the `structure` key; the
   `taskInput` distinguishes them (`pipeline: tika` vs `pipeline: docling`), so each sees the
   other's entry as stale. Per the convention (§36, one current representation per type, last
   writer wins), a Java run **overwrites** a docling payload it finds.
2. **Manifest shape follows the convention literally**, not the current Java or Python shape.
3. **Per page: sanitized XHTML plus Markdown derived from that same sanitized string.** The
   Markdown bytes are therefore unchanged from the PoC, whose `pageToMarkdown` is already
   `convert(sanitize(page))`; the sanitized fragment it discards is what we persist.
4. **Bare `--artifacts` includes structure** (`--artifacts` = raw + structure). Operators wanting
   the previous behaviour pass `--artifacts=raw`. Accepted cost: an existing ARTIFACT invocation
   gains a full second Tika parse per document plus two files per page.
5. **`taskInput.version` is Tika's runtime version**, so a Tika upgrade invalidates structure
   artifacts fleet-wide and provenance is honest. Source: `Tika.getString()`, which reads
   `/META-INF/maven/org.apache.tika/tika-core/pom.properties` (confirmed in the 3.3.0 bytecode)
   and so survives the assembly-merged jar. extract-lib uses the same idiom at
   `TikaDocument.java:60` and strips the `Apache Tika ` prefix at `:292`.
6. **A document with no page divs is one filesystem-paginated page**, `page-1.*`, not the
   convention's single-document `content.md` form. §162 assigns filesystem pagination to the Tika
   producer, and one code path beats two.
7. **No `sanitizeServedDocument`.** The issue asks to keep it for #2228, but produce-time
   sanitizing leaves the serving side nothing to clean. #2228 can add defense-in-depth itself.

## Scope

**In scope**

1. `StructureMarkdownExtractor` carried over, returning per-page (XHTML, Markdown) pairs.
2. `StructureArtifact implements Artifact`, registered in the catalog.
3. `ArtifactPath` helpers for the `structure/` directory and `page-%d.<ext>` files.
4. Manifest records reshaped to the convention (`pages` + nested `pagination`).
5. The flexmark dependency and the reactor-wide jsoup pin.
6. Tests: extractor unit tests (carried over), on-disk shape, determinism, embedded exclusion,
   manifest shape, legacy-payload overwrite, stale-page cleanup.

**Out of scope**

- Serving, `?format=md|xhtml`, `ArtifactResource` (#2228).
- The `pages` (plain text) type (#2227).
- INDEX-time structure production: the INDEX hook records manifests for payloads extract-lib
  writes during the parse, which is `raw` only (finding recorded in #2225).
- Converging `datashare-python` and updating the convention doc's `transcription` gap.
- `label` and `confidence` manifest fields: Tika supplies neither.

## Component 1: `StructureMarkdownExtractor` (datashare-index, `text/structure`)

Carried over from the PoC unchanged in behaviour: parse once with `AutoDetectParser` into
`ToXMLContentHandler`; `PDFParserConfig.OCR_STRATEGY.NO_OCR` and `IdentityHtmlMapper` in the
`ParseContext`; a content-type hint passed only when it is specific (never
`application/octet-stream`); `Safelist.relaxed().removeTags("u").preserveRelativeLinks(true)`;
flexmark with `SETEXT_HEADINGS false` and `MAX_BLANK_LINES 1`; empty-paragraph removal before
conversion; root-owned page selection that skips `div.embedded, div.email-entry,
div.package-entry` (the DOM analogue of `PageIndicesContentHandler`'s `embeddedLevel == 0` guard),
falling back to the whole body when no page div exists.

Changed API:

```java
public record Page(String xhtml, String markdown) {}
public List<Page> extract(InputStream source, String contentType) throws ...
```

Per page, in order: sanitize the live element once; wrap that sanitized fragment in a minimal
`<html xmlns="http://www.w3.org/1999/xhtml"><body>` shell serialized with jsoup's XML syntax, so
the `.xhtml` file is genuinely parseable as `application/xhtml+xml`; convert the same sanitized
string with flexmark and strip it. Removed from the PoC: the whole-document `xhtml` result field,
`extractPages()`, `sanitizeServedDocument()`.

Determinism holds as before: fixed Tika version, OCR off, embedded pages excluded, pinned
flexmark options, deterministic jsoup serialization. Same bytes give byte-identical files, which
is what the content-addressed cache relies on.

## Component 2: `StructureArtifact` (datashare-index, `text/artifact`)

```java
ArtifactType type()            -> ArtifactType.STRUCTURE
Map<String,Object> taskInput() -> {"pipeline": "tika", "version": tikaVersion()}
ManifestEntry produce(ctx)     -> ManifestEntry.paginated(taskInput(), Pages.filesystem(n))
```

`produce()`:

1. Open `ctx.sources().getSource(ctx.project(), ctx.document())`. For a root this is the original
   file; for an embed, `SourceExtractor.getEmbeddedSource` re-extracts it from its root against
   the artifact cache (`SourceExtractor.java:76`).
2. Extract the pages.
3. Replace the `structure` path with `FileUtils.deleteQuietly` (commons-io 2.18.0, already
   compile-scope in datashare-index and used in datashare-app), which handles both the convention's
   directory and docling's flat file in one call, then `Files.createDirectories`. This covers
   decision 1's overwrite and removes stale `page-N` files a previous, longer run left behind.
   A payload that survives deletion is not silently ignored: `createDirectories` then throws
   `FileAlreadyExistsException`, which becomes an `ArtifactException`. Sibling types in the same
   document directory (`raw`, `raw.json`, `manifest.json`) are untouched.
4. Write `page-%d.xhtml` and `page-%d.md`, 1-based, contiguous.
5. Return the paginated entry. `ArtifactProducer` stamps `COMPLETE` and writes it last, under the
   per-document lock, so a crash mid-write leaves no complete entry and the next run regenerates.

`tikaVersion()` is `Tika.getString()` with the `Apache Tika ` prefix stripped.

An empty page list cannot happen: the extractor falls back to the whole body, so a parseable
document always yields at least one page.

## Component 3: `ArtifactPath` helpers (datashare-index)

```java
public static final String STRUCTURE_DIR = "structure";
public static Path structureDir(Path docArtifactDir)
public static Path structurePage(Path docArtifactDir, int page, String extension)
```

Keyed on `docArtifactDir` (what `produce()` and `ArtifactContext` carry) rather than
`projectRoot` + digest, mirroring how `RawArtifact` resolves `ArtifactPath.RAW_FILE`. Serving in
#2228 calls `dir(projectRoot, digest)` first, as it must anyway for `manifest.json`.
`structurePage` formats `page-%d.<extension>`, 1-based and **unpadded**, so a reader builds a
filename from the page index it already holds instead of having to know a padding width that could
drift from the writer's. Padding only bought lexicographic listing order, which nothing depends on:
the producer writes by loop index and readers resolve pages through the manifest. Settled in the
convention (`document-artifacts-convention.md`, "no zero-padding") after PR #2299's review, once
datashare-python confirmed it writes byte ranges only, leaving Java the sole writer of this scheme.
The formatting still pins `Locale.ROOT`, since a plain `%d` under an `ar-EG` default locale writes
`page-١٢.md`. Consequence, accepted: `ls structure/` is no longer numerically ordered past nine
pages. Extensions are fixed by type (convention §71): `.xhtml` is `application/xhtml+xml`, `.md` is
`text/markdown`.

## Component 4: manifest records reshaped (datashare-api)

The convention's entry shape is `pages: {total, pagination: {type, ranges}}`. Java currently has
`pagination: {type, total, byteRanges}` (`ManifestEntry.java:12`, `Pagination.java`). No
Java-written manifest contains the key (`RawArtifact` only emits `singleFile`/`empty` entries and
`@JsonInclude(NON_NULL)` drops the null), so this is a free rename with no migration.

```java
public record Pages(int total, Pagination pagination) {
    public static Pages filesystem(int total)          // pagination {"type":"filesystem"}
    public static Pages byteRanges(List<long[]> ranges) // total = ranges.size()
}
public record Pagination(String type, List<long[]> ranges) { ... }
```

`ManifestEntry.pagination` becomes `pages`; `ManifestEntry.total()` reads `pages.total()`. Both
records keep `@JsonInclude(NON_NULL)`, which is what lets a single-document entry omit
`pagination` entirely as the convention requires (§126) and lets a single-file entry such as
`raw` omit `pages`.
`Pages.byteRanges` derives `total` from the list, which enforces by construction what Python's
`byte_ranges_length_should_match_total` validator checks. Deriving it is also why `byteRanges`
stays in the API despite having no Java producer: #2228 must read docling-written entries.

Resulting entry for a 12-page PDF:

```json
"structure": {
  "status": "complete",
  "pages": { "total": 12, "pagination": { "type": "filesystem" } },
  "taskInput": { "pipeline": "tika", "version": "3.3.0" }
}
```

## Component 5: registration and dependencies

- The catalog moves out of `ArtifactTask.java:136` into `ArtifactRegistry.withDefaults()`, which
  returns `new ArtifactRegistry(List.of(new RawArtifact(), new StructureArtifact()))`. Same name
  and signature PR #2262 introduces, deliberately: the conflict then resolves to "add
  `StructureArtifact` to that list" instead of two divergent wirings. It also makes the default
  catalog unit-testable, which an inline `new ArtifactRegistry(...)` inside `runWorker` is not.
  Insertion order matters: `ArtifactRegistry` keeps a `LinkedHashMap` (`ArtifactRegistry.java:13`),
  so with bare `--artifacts` raw runs first and populates the embedded-source cache structure
  then reads.
- Root `pom.xml` `dependencyManagement`: flexmark `0.64.8` and jsoup `1.22.1`. The jsoup pin is
  load-bearing, not hygiene: flexmark-html2md pulls jsoup 1.15.4, which wins Maven mediation and
  downgrades the 1.22.1 that Tika 3.3.0 manages. The older jsoup lacks
  `org.jsoup.parser.TagSet`, so Tika's `JSoupParser` fails to instantiate, its SPI registration is
  silently dropped, and `text/html` falls back to `TextAndCSVParser`, which HTML-escapes whole
  documents instead of parsing their structure. Reactor-wide because datashare-app also uses Tika.
- `datashare-index/pom.xml`: `flexmark-html2md-converter`.

## Data flow

```
ARTIFACT stage worker (ArtifactTask.runWorker)
  -> registry.select(--artifacts)            [raw, structure]
  -> per queued document, ArtifactProducer.run(selected, context, force)
       raw:       extract-lib writes raw/raw.json, entry recorded
       structure: skip-if-current? (terminal entry with an equal taskInput)
                  -> StructureArtifact.produce
                       SourceExtractor.getSource
                       StructureMarkdownExtractor.extract   (Tika -> jsoup -> flexmark)
                       replace structure/, write page-N.{xhtml,md}
                  -> ManifestEntry(pages: filesystem N)
       -> repository.put(docArtifactDir, "structure", COMPLETE entry)   [under lock, last]
```

## Error handling

Nothing new. A parse, read, or write failure throws `ArtifactException`, which
`ArtifactProducer.java:54` already catches per type per document: it logs with the document id,
leaves no manifest entry, and returns false, which `ArtifactTask` counts into `nbFailed` and
reports at the end with re-run guidance. Because the failed document has no terminal entry,
skip-if-current is false for it and a plain re-run retries exactly those documents, with no need
for `--artifactsForce`. A structure failure never affects `raw` in the same document, nor any
other document.

## Tests (TDD, RED before implementation)

`StructureMarkdownExtractorTest` carries over from the PoC, adapted to the `List<Page>` return:
page splitting, embedded pages excluded, sanitization (script/style/handler stripping, relative
links kept), empty-paragraph removal, ATX headings, OCR-off determinism.

One new `StructureArtifactTest` holds every producer case, including the two that need the real
`ArtifactProducer` + `FilesystemManifestRepository`, so the fixtures (temporary dir, document,
mocked `SourceExtractor`) are declared once:

- writes `page-1.{xhtml,md}` .. `page-N.{xhtml,md}`, 1-based and contiguous;
- the `.xhtml` file parses as XML and its Markdown sibling derives from the same sanitized HTML;
- producing twice yields byte-identical files (determinism);
- a root's page count excludes its embedded children's pages;
- the returned entry carries `pages.total`, `pagination.type = filesystem`, and
  `taskInput = {pipeline: tika, version: <Tika>}`;
- a pre-existing flat `structure` file (docling layout) is replaced by the directory;
- a stale `page-9.md` from a previous longer run is gone after a shorter run;
- an unreadable source throws `ArtifactException` and creates no `structure/` directory;
- through the real `ArtifactProducer`: the written `manifest.json` matches the convention shape, and
  a second run skips a document whose entry is already current.

`ManifestEntryTest` and `PaginationTest` updated for `Pages`: a filesystem entry serializes as the
convention's nested shape, a docling-written byteRanges entry deserializes (the only reason `ranges`
exists in the record, so it gets no factory), and a single-file entry still omits the `pages` key.

## Risks and notes

- **Cost.** Decision 4 makes bare `--artifacts` twice as expensive per document, and structure on
  an OST's embeds is only cheap when `raw` is cached first. `--artifacts=structure` alone on a
  289-embed OST re-parses the root once per embed. Not guarded; documented.
- **Fleet-wide invalidation.** Decision 5 means the next Tika bump restages every structure
  artifact. Intended, per convention §86.
- **Byte-determinism is per Tika version**, which is exactly what `taskInput` records.
- **Manifest lock granularity.** Structure production is slower than raw, so it widens the
  window the existing coarse produce-time lock was already flagged for (epic follow-up
  `artifact-manifest-locking`). Production runs outside the lock (`ArtifactProducer.java:46`),
  so only the manifest merge serializes; no change needed here.

## Follow-ups (not in this PR)

1. `datashare-python`: write `structure/content.md` instead of a flat `structure` file, and nest
   pagination as `pages.pagination` with `ranges`, per the convention.
2. Convention doc: add the `transcription` type, fix the trailing commas in the example JSON
   (§102, §110), and reword §122, which says `total` "replaces the old self-referential `pages`
   count" while the example nests `total` inside a key named `pages`.
3. `datashare-python`: make the manifest read-modify-write concurrency-safe (§138).
