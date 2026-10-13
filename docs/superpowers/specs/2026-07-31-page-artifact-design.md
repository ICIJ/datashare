# Design: `page` artifact (paginated plain text on disk) (#2227)

Part of epic(artifacts) #2229. Follows the merged foundation (#2223 type abstraction + registry,
#2224 manifest.json, PR #2231) and stacks on #2226 (`structure`). Produce-only: serving is #2228.

Normative reference: the Document artifacts storage convention,
`technology/services/data-engineering-ml/document-artifacts-convention.md` in the ICIJ docs repo.

## Goal

Persist a document's paginated **plain** extracted text under `artifactDir` as the `page` type,
produced from the ARTIFACT stage, gated by `--artifacts`, and indexed by the document's
`manifest.json`. Datashare paginates plain text live today (`Extractor.extractPages`); this stores
it so a page can be served with one read instead of a parse.

## Current state

**On main.** The foundation is in place and `RawArtifact` is the only wired producer.
`ArtifactType` declares `RAW` and `STRUCTURE` only. `ArtifactProducer` already owns the uniform
skip-if-current → produce → stamp-terminal → record-under-lock loop and needs no change for a third
producer. The catalog is built at `ArtifactTask.java:136`; `ArtifactContext` carries
`(project, document, docArtifactDir, sources)`.

**Live pagination.** `DocumentResource.getPages` (`:199-214`) and `getContentByPage` (`:224-240`)
build an `Extractor` per request (`getExtractor`, `:506-518`), call `extractor.disableOcr()` when
`doc.getOcrParser() == null`, then pass `doc.getPath()` — the *root* file — plus a metadata
`DocumentSelector` for a non-root document. In extract-lib,
`PagesContentHandler extends PageIndicesContentHandler`, so one parse holds both the per-page text
and the per-page char offsets, but the public API (`extractPages` / `extractPageIndices`) exposes
only one at a time.

**A page-indices cache already exists in the artifact dir.**
`Extractor.extractPageIndices(path, selector, docId)` (`Extractor.java:848-860`) writes
`pages.json` into the document's content-addressed directory whenever `embedOutput` is set, and
reads it back on the next call. It is a sibling file of `manifest.json`, not part of the manifest.

**Cross-repo.** The convention's table names the type `pages` while its serving section routes
`/artifacts/page/:id`. #2228 committed to the route spelling.

**In flight elsewhere.** #2226 (`feat/artifacts-structure`) and #2228 (`feat/artifacts-resource`)
are being implemented in parallel sessions, both branched off main. #2226 reshapes the manifest
into the convention's `pages: {total, pagination: {type, ranges}}` and moves the catalog into
`ArtifactRegistry.withDefaults()`. #2228 adds `ArtifactType.PAGE("page")`,
`ArtifactPath.payloadDir/payloadPage/payloadContent`, and an `ArtifactReader` that already reads
both pagination schemes. #2225 (PR #2262) is unmerged and not depended on.

## Decisions

1. **Stack on #2226.** byteRanges (decision 3) needs its `Pages`/`Pagination` reshape, and this
   branch must add itself to its `withDefaults()`. Merging in the epic's order
   (2226 → 2227 → 2228) then needs no rewrite of the manifest entry. #2226 ships only
   `Pages.filesystem(total)` and `Pagination.filesystem()`, deliberately (nothing in Java wrote
   byte ranges yet), so this branch adds the two `byteRanges` factories.
2. **Per-document source bytes, not the root-plus-selector path.** Root → `doc.getPath()`;
   embedded → its own cached `raw` payload at `docArtifactDir/raw`, which is exactly where
   extract-lib caches it (`EmbeddedDocumentExtractor.getEmbeddedPath` delegates to
   `EmbeddedArtifactWriter.rawPath`, `EmbeddedDocumentExtractor.java:136-137`). Both paths go
   through extract-lib's `Extractor.extractPages(path)`, so OCR wiring, parse timeout, and digester
   config come for free and are not reimplemented. The live path costs a full root parse per
   embedded document: 289 root parses for a 289-embed OST, and each one re-runs OCR on the whole
   root. It does produce the embed's own pages today
   (`DocumentResourceTest.java:472-500` asserts two pages for a PDF embedded in an `.eml`), so the
   reason to leave it is cost, not correctness.
   An embed whose `raw` is absent gets it from `context.sources().getSource(project, document)`,
   which populates the cache as a side effect; still absent afterwards is an `ArtifactException`.
3. **byteRanges pagination.** One `pages/content.txt` holding the concatenated page text, with
   half-open `[start, end)` **byte** offsets accumulated while writing. Correct by construction:
   the offsets are the byte counts actually written, so `ranges` cannot disagree with the file. One
   inode per document instead of one per page, which matters on the EFS deployments, and a page is
   served with a single positional read. `Pages.byteRanges(ranges)` derives `total`.
4. **OCR-aware, mirroring the live endpoint.** `doc.getOcrParser() == null` → `disableOcr()`, so
   a document indexed without OCR is paginated without OCR and its pages match its indexed content.
5. **`taskInput = {"pipeline": "tika", "version": <Tika runtime version>, "ocr": <run's ocr option>}`.**
   Same idiom and the same intentional fleet-wide invalidation on a Tika bump as #2226.
   `version` is `Tika.getString()` with the `Apache Tika ` prefix stripped. The `ocr` value is the
   **run's** `--ocr` setting (`DatashareCliOptions.OCR_OPT`, default `true`), not the per-document
   state from decision 4: `Artifact.taskInput()` takes no document, and its contract forbids
   per-document data so that the same document with the same config compares equal across batches.
   Consequence, accepted: re-indexing the same file with OCR turned on yields the same content
   digest and hence the same artifact dir, so an ARTIFACT re-run with an unchanged `--ocr` sees the
   old non-OCR pages as current. `--artifactsForce` is the remedy.
6. **No page divs → `ManifestEntry.empty(taskInput())`.** `PagesContentHandler` only appends a page
   on `endPage`, so a `.txt` file or a plain email body yields zero pages — which is what the live
   endpoint returns today (`[]`). `EMPTY` is terminal, so the document is recorded once and not
   reprocessed, and #2228 serves it as 404. No synthetic single page: `extractPages` discards the
   body text into a null writer, so there is nothing to fall back on without a second parse. This
   deliberately differs from #2226's whole-body fallback, where the extractor still has the text.
7. **The `--artifacts` all-set includes `page`**, per the issue. With #2226 that makes bare
   `--artifacts` three Tika parses per document. `page` cannot share `structure`'s parse: structure
   is OCR-off by design and emits sanitized XHTML, `page` is OCR-aware and emits Tika's plain body
   text. Accepted and documented, not guarded.
8. **The payload is one file, replaced atomically.** Write a per-attempt temp file in `pages/`
   (`Files.createTempFile`, so two producers on the same document cannot truncate each other's
   bytes into a NUL hole), then
   `Files.move(tmp, content.txt, ATOMIC_MOVE, REPLACE_EXISTING)`, so a failure before the move
   leaves the previous payload intact and matching its still-`complete` manifest entry, rather than
   a truncated `content.txt`. A failure after the move (the caller's `repository.put`, e.g. a
   manifest lock timeout) can instead leave the new payload live while the manifest still describes
   the previous one; guarding against that is a consumer-side invariant
   (`ranges.getLast()[1] == Files.size(content)`, else treat the entry as not ready) that belongs to
   #2228, not this producer. No directory swap and no stale-`page-N.txt` sweep,
   unlike #2226's structure producer: this type has exactly one payload file, nothing writes
   filesystem-paginated `page` today, and a `page-N.txt` left by some future producer cannot
   mislead a reader that resolves the scheme from the manifest. It would be dead bytes, not a bug.
   Sibling types in the same document directory (`raw`, `raw.json`, `structure/`, `pages.json`,
   `manifest.json`) are untouched.
9. **`pages.json` is left alone.** `/documents/pages` keeps using extract-lib's own cache. Writing
   it here would cost a second parse per document, since the public API cannot return text and
   offsets together even though one handler computes both. Follow-up, in extract-lib.
10. **Manifest key `page`, directory `pages/`.** Matches the convention's serving routes, the issue
    title, and #2228's `ArtifactType.PAGE`. The convention's type table says `pages`; that is a
    doc fix (Follow-ups), not a second spelling in the code.

## Scope

**In scope**

1. `PageArtifact implements Artifact` (datashare-index), registered in the default catalog.
2. `ArtifactType.PAGE("page")`.
3. `ArtifactPath`: `PAGES_DIR`, `pagesDir`, `pagesContent`.
4. `Pages.byteRanges(ranges)` and `Pagination.byteRanges(ranges)` (datashare-api).
5. `ArtifactRegistry.withDefaults(PropertiesProvider)` gaining `PageArtifact`.
6. Tests: on-disk shape, range arithmetic, parity with `Extractor.extractPages`, embedded
   production from the `raw` payload, the page-less case, OCR state in `taskInput`, manifest shape
   through the real producer, stale-payload replacement, failure isolation.

**Out of scope**

- Serving (#2228), including any `/artifacts/page` route.
- Migrating or re-backing `/documents/pages` and `/documents/content/pages`.
- Writing `pages.json` (decision 9) and any extract-lib change.
- INDEX-time production: the INDEX hook records manifests for payloads extract-lib writes during
  the parse, which is `raw` only (finding recorded in #2225).
- `label` and `confidence`: Tika supplies neither.

## Component 1: `PageArtifact` (datashare-index, `text/artifact`)

```java
ArtifactType type()            -> ArtifactType.PAGE
Map<String,Object> taskInput() -> {"pipeline": "tika", "version": tikaVersion(), "ocr": ocrEnabled}
ManifestEntry produce(ctx)     -> ManifestEntry.paginated(taskInput(), Pages.byteRanges(ranges))
```

Constructed with the `PropertiesProvider` (the only producer that needs it, for extractor options).
`produce()`:

1. Resolve the source path: `document.getExtractionLevel() <= 0` → `document.getPath()`; otherwise
   `docArtifactDir/raw`, ensured as in decision 2.
2. Build an `Extractor` in try-with-resources from the merged properties, **without**
   `embedOutput`: page production writes only its own payload and needs neither the `pages.json`
   cache nor embed artifact writing. `disableOcr()` when the document has no `ocrParser`. One
   `Extractor` per document, as the live endpoint does per request, because `disableOcr()` is a
   one-way flag and closing it releases any OCR pool.
3. `extractPages(path)`. Empty → return `ManifestEntry.empty(taskInput())` and write nothing.
4. Stream each page's UTF-8 bytes into a per-attempt temp file in `pages/`, accumulating
   `[offset, offset + length)` per page, then move it onto `content.txt` (decision 8).
5. Return the paginated entry. `ArtifactProducer` stamps `COMPLETE` and writes it last under the
   per-document lock, so a crash mid-write leaves a payload no consumer can see.

`ocrEnabled` in `taskInput` is the run's `ocr` option, per decision 5; step 2's per-document
`disableOcr()` is what makes the text right, and is not part of the fingerprint.

## Component 2: `ArtifactPath` additions (datashare-index)

```java
public static final String PAGES_DIR = "pages";
public static Path pagesDir(Path docArtifactDir)      // pages/
public static Path pagesContent(Path docArtifactDir)  // pages/content.txt
```

Siblings of #2226's `structureDir` / `structurePage`, keyed on `docArtifactDir` the same way. Not
the type-keyed `payloadDir(dir, type)` / `payloadContent(dir, type, ext)` pair #2228's spec plans:
this producer only ever writes one type in one extension, so the dispatch would have a single real
caller here. #2228 needs the type-keying because it serves several types, and it collapses these
three helpers into its own when it lands. Extensions are fixed by type (convention): `page` → `txt`.

## Component 3: registration

`ArtifactRegistry.withDefaults(propertiesProvider)` returns
`new ArtifactRegistry(List.of(new RawArtifact(), new StructureArtifact(), new PageArtifact(propertiesProvider)))`.
The parameter is new: #2226 and #2225 both introduce a no-arg `withDefaults()`, and PageArtifact is
the first producer needing extractor options. Insertion order matters, because `ArtifactRegistry`
keeps a `LinkedHashMap`: `raw` runs first and populates the embedded-source cache that `structure`
and `page` then read, so bare `--artifacts` extracts an embed's bytes once.

## Data flow

```
ARTIFACT stage worker (ArtifactTask.runWorker)
  -> ArtifactRegistry.withDefaults(props).select(--artifacts)     [raw, structure, page]
  -> per queued document, ArtifactProducer.run(selected, context, force)
       raw:       extract-lib writes raw/raw.json, entry recorded
       structure: Tika XHTML + Markdown per page                  (#2226)
       page:      skip-if-current? (terminal entry, equal taskInput)
                  -> PageArtifact.produce
                       source path = original file | docArtifactDir/raw
                       Extractor.extractPages(path)               (OCR per doc.ocrParser)
                       replace pages/, write content.txt + accumulate ranges
                  -> ManifestEntry(pages: {total, pagination: {type: byteRanges, ranges}})
       -> repository.put(docArtifactDir, "page", COMPLETE entry)   [under lock, last]
```

Resulting entry for a 3-page document:

```json
"page": {
  "status": "complete",
  "pages": {
    "total": 3,
    "pagination": { "type": "byteRanges", "ranges": [[0, 125], [125, 39000], [39000, 48909]] }
  },
  "taskInput": { "pipeline": "tika", "version": "3.3.0", "ocr": false }
}
```

## Error handling

Nothing new. A read, parse, or write failure throws `ArtifactException`, which
`ArtifactProducer.java:54` already catches per type per document: it logs with the document id,
leaves no manifest entry, and returns false, which `ArtifactTask` counts into `nbFailed` and
reports with re-run guidance. Because a failed document has no terminal entry, skip-if-current is
false for it and a plain re-run retries exactly those documents, without `--artifactsForce`. A
partially written `content.txt` is invisible: the manifest entry gates the whole type and is
written last. A `page` failure never affects `raw` or `structure` in the same document, nor any
other document.

## Tests (TDD, RED before implementation)

`PageArtifactTest` (datashare-index) holds every case, so the fixtures (temporary artifact dir,
document, `SourceExtractor`) are declared once:

- a root PDF writes `pages/content.txt` and no `page-*` files;
- the recorded ranges are half-open, contiguous (`ranges[i][1] == ranges[i+1][0]`), start at 0, and
  end at the file length; each range's slice decodes to that page's text;
- `total` and the page texts match `Extractor.extractPages` on the same file (parity with the live
  endpoint for a root document);
- an embedded document produces from its own `raw` payload, with the root file absent from the
  fixture, proving the root is not re-parsed;
- an embedded document with no `raw` payload and no reachable root throws `ArtifactException` and
  writes no `pages/`;
- a document with no page divs records `EMPTY` and creates no `pages/` directory;
- `taskInput` records the run's `ocr` setting, and a run with `--ocr false` produces a task input
  that is not equal to a default run's, so one does not skip the other. Nothing asserts the
  per-document `disableOcr()` call itself: proving it needs either a tesseract-dependent image
  fixture or an extractor seam whose test would verify a mock call rather than a behaviour. Recorded
  as a known gap in the plan.
- a regeneration that fails leaves the previous `content.txt` byte-identical and no `.tmp` behind;
- through the real `ArtifactProducer` + `FilesystemManifestRepository`: the written `manifest.json`
  carries the shape above under the `page` key with `status: complete`, and a second run skips.

`ArtifactPathTest` gains cases for `payloadDir` and `payloadContent`.

TDD order: `ArtifactPath` helpers → `PageArtifact.produce` (root, then ranges, then embedded, then
empty) → registration.

## Verification

```
mvn -pl datashare-index -am test -Dtest=PageArtifactTest,ArtifactPathTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl datashare-app -am test -Dtest=ArtifactTaskTest,ArtifactRegistryTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Known limitations, recorded not fixed

- **Non-inline embed text bleeds into a container's page text.** `Extractor.extractPages` hands
  `EmbedSpawner` the same `PagesContentHandler` instance for spawned embeds
  (`EmbedSpawner.java:409`, `handlerFunction.apply(writer)`), and `characters` counts whenever
  `embeddedLevel >= 0`, so a `.docx` with an embedded spreadsheet gets that spreadsheet's text
  appended to a page. The `embeddedLevel == 0` guard stops the embed's *pages* from being recorded,
  not its characters. Pre-existing live behaviour, which the artifact therefore matches; the fix is
  an extract-lib `extractPages` that gives spawned embeds a throwaway handler. Inline embeds (OCR'd
  images in a PDF) are concatenated on purpose and must stay that way.
- **No filename hint when parsing an embed's `raw` file.** The `Extractor` API takes only a `Path`,
  so Tika detects the type from magic bytes and the sidecar `raw.json` metadata is unused. The
  formats where detection is ambiguous are text formats, which have no page divs and record `EMPTY`
  either way.
- **`content.txt` is not guaranteed byte-equal to ES `content`.** Characters outside `div.page` are
  counted by the handler but stored in no page, and the handler carries a trim hack simulating
  `ElasticsearchSpewer`. So this artifact is a paginated rendering, not an offset-exact mirror of
  the indexed content: NER highlighting must stay anchored to `content`.
- **Cost.** Decision 7 adds a third full Tika parse per document to bare `--artifacts`, and it is
  OCR-enabled for documents indexed with OCR, so it is the most expensive of the three.
- **A re-run that yields zero pages over a previously paginated document leaves the old payload on
  disk.** The entry becomes `EMPTY` while the previous `pages/content.txt` stays where it is.
  Manifest-driven readers 404 the entry, so these are dead bytes rather than wrong data, consistent
  with decision 8's stance on stray files.

## Follow-ups (not in this PR)

1. extract-lib: one parse returning both page text and page indices, then write `pages.json` from
   this producer and back `/documents/pages` with the artifact (also #2228's follow-up 2).
2. extract-lib: isolate spawned embeds behind their own content handler in `extractPages`, fixing
   the bleed above for both the live endpoint and the artifact.
3. Convention doc: rename the type table's `pages` to `page` to match the routes and the manifest
   key, and note that `page` uses byteRanges in the Java producer.
4. When #2228 lands, collapse `ArtifactPath.structureDir`/`structurePage`/`pagesDir`/`pagesContent`
   into its type-keyed `payloadDir`/`payloadPage`/`payloadContent`, so writer and reader resolve a
   payload through one method.
