# Design: dedicated `ArtifactResource` (#2228)

Part of epic(artifacts) #2229. Follows the merged foundation (#2223 type abstraction + registry,
#2224 manifest.json, PR #2231) and serves what #2226 (`structure`) and #2227 (`page`) produce.

Normative reference: the Document artifacts storage convention,
`technology/services/data-engineering-ml/document-artifacts-convention.md` in the ICIJ docs repo.
Its §Serving was already updated to the `/artifacts/…` shape (recorded in the issue comment on
#2228); this design implements that section.

## Goal

One dedicated resource that serves every artifact type from disk at
`/:project/artifacts/<type>/:id`, with a single access-control decision, manifest-driven page
lookup, and no per-type serving logic scattered across `DocumentResource`.

## Current state

**On main.** `ArtifactType` (RAW, STRUCTURE), `ManifestEntry` / `ManifestEntryStatus` /
`Pagination`, `ManifestRepository` + `FilesystemManifestRepository`, and `ArtifactPath` with only
`dir()`, `manifest()`, `RAW_FILE`, `RAW_SIDECAR_FILE`. `ArtifactTask.java:140,178` resolves a
document's artifact dir as `ArtifactPath.dir(artifactDir.resolve(project.name), doc.getId())` —
the read side must resolve it identically. `ManifestRepository` is never Guice-bound: `ArtifactTask.java:139`
instantiates `FilesystemManifestRepository` directly, and the read side does the same.
`ManifestEntryStatus`: only `COMPLETE` is `isServable()`; `EMPTY` records "processed, no payload
here" (a root document's `raw`).

**In `DocumentResource`** (579 lines): `/documents/src` GET (`:107-113`) and HEAD (`:129-133`)
share one gate (`:138-151`: project grant, `Project.isAllowed(project, clientAddress)` → 403,
`DocumentVerifier.isRootDocumentSizeAllowed` → 413) and one payload builder (`:539-559`,
including the `image/ocr-*` content-type correction). `/documents/pages` (`:198-214`) returns
`PageIndices`; `/documents/content/pages` (`:224-240`) returns `List<String>`. Both reparse the
source live. `getArtifactPath` (`:576-578`) maps `ARTIFACT_DIR_OPT` + project to the project root.

**In the PoC** (`poc/tika-document-structure-extraction`): `/documents/structure/*` endpoints with
`resolveStructureDir`, contiguous page counting, `parsePageNumber`, and `sanitizeServedDocument`.
Those routes never landed on main, so "remove the PoC endpoints" is a no-op here; the reusable
parts are the resolution and page-number rules, not the code.

**Consumers.** `datashare-client` calls `/documents/pages` (`src/api/index.js:171`) and
`/documents/src` (`src/api/resources/Document.js:229,243`). `/documents/content/pages` has **zero**
usages across datashare-client, datashare-tarentula, datashare-docs, datashare-python, and
datashare-extension-dataconnect.

**In flight elsewhere.** #2226 and #2227 are being implemented in a parallel session
(`docs/superpowers/specs/2026-07-31-structure-artifact-design.md`). Three of its decisions bind
this design and are handled under "Cross-PR coupling" below: it owns the `structure/` path
helpers, it reshapes `ManifestEntry.pagination` into `pages: {total, pagination: {type, ranges}}`,
and it sanitizes XHTML at produce time (its decision 7 drops `sanitizeServedDocument` and hands
defense in depth to this issue). #2225 (PR #2262, `--artifacts` in INDEX + ARTIFACT) is also
unmerged; this design does not depend on it.

## Decisions

1. **Branch off main, serve from the manifest.** The resource reads `manifest.json` and resolves
   payloads from the pagination scheme, so it needs no `StructureArtifact` / `PageArtifact` class
   and merges independently of #2226/#2227. Tests use hand-written manifests plus payload files as
   fixtures.
2. **Derived types are strict-store; `raw` is source-backed.** `page` and `structure` serve only
   what is on disk with a `COMPLETE` entry, and 404 otherwise, per the convention. `raw` serves
   through `SourceExtractor` exactly as `/documents/src` does, because a root document's `raw`
   entry is `EMPTY` by design and strict gating would 404 every root document. One resource, two
   consistency rules, deliberately.
3. **`raw` reuses the download gate, it does not copy it.** The grant + download-IP + root-size
   decision and the payload builder move out of `DocumentResource` into one injectable
   collaborator both resources call. A second copy of that rule would drift, and the drift is a
   download-restriction bypass.
4. **`/documents/src` and `/documents/pages` are not migrated.** `/documents/src` stays the
   download route, untouched; `/artifacts/raw` is an additional path to the same gated bytes.
   `/documents/pages` returns char-offset ranges into the extracted text the client highlights
   against, which is *not* what the `page` artifact stores (per-page text files); deriving offsets
   by summing page-text lengths is only sound if it matches ES `content` exactly, and the epic's
   own gotcha list says to keep highlight anchored to `content`. Follow-up, not this issue.
5. **`/documents/content/pages` is deprecated in place, not re-routed.** Its live implementation
   stays verbatim and gains `@Deprecated`, a `Deprecation` header, and an OpenAPI note pointing at
   `/artifacts/page`. Re-routing it to the strict-store handler would silently drop a working live
   feature for every project indexed without `--artifacts`.
6. **Both pagination schemes are read.** `Pagination` already models `filesystem` and
   `byteRanges`, the parallel session has not committed to one for `page`, and datashare-python's
   docling producer writes byte ranges. Supporting both is about ten lines and removes a cross-PR
   dependency.
7. **`?format=xhtml` ships, with headers as the only serving-side defense.** #2226 sanitizes at
   produce time, so no jsoup and no serve-time safelist are needed. XHTML is served as
   `application/xhtml+xml` with `Content-Security-Policy: default-src 'none'; sandbox` and
   `X-Content-Type-Options: nosniff`. **Residual risk, accepted and recorded:** a payload written
   by the non-conformant datashare-python docling producer is sanitized by nobody, and response
   headers do not protect a client that fetches the fragment and injects it into its own DOM.
   Client-side sanitization is a follow-up on the client.
8. **An unsupported `format` value is 400, not 404.** 404 on this route means "no such page"; a
   bad format is a request error and gets a message listing the supported values.
9. **The document is always resolved through the indexer first.** The path `id` is used to look up
   the document in the requested project before any disk access, so an unknown id 404s and a user
   cannot probe arbitrary digests, or another project's digests, by URL.
10. **`total` is trusted; a missing payload 404s the page, not the manifest.** The page count comes
    from the entry, not from re-counting files on disk: the producer writes payloads first and the
    `COMPLETE` entry last, which is exactly what makes the manifest authoritative. The PoC's
    contiguous on-disk counting existed because it had no manifest. A page whose file is missing
    404s and logs at warn, so a manifest/disk disagreement is visible rather than silently
    reshaping the advertised page count on every request.

## Scope

**In scope**

1. `ArtifactResource` with the five routes below, registered in `LocalMode` and `ServerMode`.
2. `ArtifactReader` (datashare-index): manifest entry lookup plus page-payload resolution for both
   pagination schemes.
3. `ArtifactPath` additions: the `pages/` siblings of #2226's `structure/` helpers, and a
   `content.<ext>` resolver for the byteRanges scheme.
4. `ArtifactType.PAGE("page")`, needed to route the `page` token.
5. `DocumentSourceAccess`: the download gate and payload builder lifted out of `DocumentResource`.
6. Deprecation metadata on `/documents/content/pages`.
7. Tests: the 403/404/400/413 matrix, both pagination schemes, response shapes, format handling,
   the raw gate, and `DocumentResource`'s existing tests still green after the lift.

**Out of scope**

- Producing anything (#2226, #2227).
- Migrating `/documents/src` or `/documents/pages` (decision 4).
- A whole-manifest endpoint (`GET /:project/artifacts/:id`), `thumbnail`, and `HEAD` on artifact
  routes: no consumer asked.
- Client changes, and converging datashare-python.

## Component 1: `ArtifactResource` (datashare-app, `web`)

`@Singleton @Prefix("/api")`, injected with `Indexer`, `PropertiesProvider`, `Repository`,
`DocumentSourceAccess`. `OpenApiResource` scans the `org.icij.datashare.web` package by
classloader (`OpenApiResource.java:41`), so the routes appear in the contract with no extra
wiring.

| Route | Success |
|---|---|
| `GET /:project/artifacts/raw/:id?routing=&inline=` | source bytes, document `contentType`, attachment unless `inline=true` |
| `GET /:project/artifacts/page/:id?routing=` | `{"pages": N}` |
| `GET /:project/artifacts/page/:id/:page?routing=` | `text/plain; charset=utf-8` |
| `GET /:project/artifacts/structure/:id?routing=` | `{"pages": N, "formats": [...]}` |
| `GET /:project/artifacts/structure/:id/:page?routing=&format=md` | `text/markdown` or `application/xhtml+xml` |

Note the collision of one word across the two layers, kept because both sides are normative: the
**response** key `pages` is the page count (convention §Serving), while the **manifest** key `pages`
is an object (`{total, pagination}`) after #2226's reshape. The resource reads `entry.total()` and
emits a number.

`routing` defaults to `id`. `formats` lists the extensions actually present on disk
(`page-1.<ext>` under filesystem pagination, `content.<ext>` under byteRanges), so the manifest
never advertises a format the page route would 404 on. `format` defaults to `md`.

Decision order, which the tests assert:

| Condition | Status |
|---|---|
| project not granted (or, for `raw`, download not allowed from this address) | 403 |
| document unknown in this project | 404 |
| `artifactDir` unset | 404 |
| no manifest, no entry for the type, or `status != COMPLETE` (includes `EMPTY`) | 404 |
| page non-numeric, `< 1`, or `> total` | 404 |
| payload file missing, or a byte range outside the file | 404 (logged at warn: manifest and disk disagree) |
| `format` not in `{md, xhtml}` | 400, message lists supported values |
| `raw`, root document over the size limit | 413 |

## Component 2: `ArtifactReader` (datashare-index, `text.artifact`)

Mirrors `ArtifactProducer` on the read side and owns every rule that involves the manifest or the
layout, so it is unit-testable against a temp dir with no web server:

```java
public class ArtifactReader {
    public ArtifactReader(ManifestRepository manifests)          // FilesystemManifestRepository
    public ManifestEntry servableEntry(Path docArtifactDir, ArtifactType type) throws IOException
    public byte[] page(Path docArtifactDir, ArtifactType type, ManifestEntry entry, int page, String extension) throws IOException
    public List<String> formats(Path docArtifactDir, ArtifactType type, ManifestEntry entry, List<String> candidates)
}
```

- `servableEntry` returns the entry only when it exists and `isComplete()`; null otherwise. The
  resource maps null to 404 and never inspects `status` itself.
- `page` validates `1 <= page <= total`, then resolves by scheme: `filesystem` reads
  `ArtifactPath.payloadPage(dir, type, page, extension)`; `byteRanges` reads the half-open
  `[start, end)` slice of `ArtifactPath.payloadContent(dir, type, extension)` with a single
  `FileChannel` positional read. Returns null for out-of-range, missing file, or a range past
  end-of-file.
- `formats` probes per scheme, because the two schemes keep an extension in different files:
  `filesystem` probes `page-1.<ext>`, `byteRanges` probes `content.<ext>`. Returns those
  present, in the candidate order given.

## Component 3: `ArtifactPath` additions (datashare-index)

#2226 adds `STRUCTURE_DIR`, `structureDir(docArtifactDir)`, `structurePage(docArtifactDir, page,
extension)`. This branch adds the `page` siblings and the byteRanges resolver, keyed on
`docArtifactDir` to match:

```java
public static final String PAGES_DIR = "pages";     // convention: type `page`, directory `pages/`
public static Path payloadDir(Path docArtifactDir, ArtifactType type)                        // pages/ or structure/
public static Path payloadPage(Path docArtifactDir, ArtifactType type, int page, String ext) // page-%d.<ext>
public static Path payloadContent(Path docArtifactDir, ArtifactType type, String ext)        // content.<ext>
```

Named `payload*` rather than `artifact*` so nothing reads as a variant of the existing
`dir(projectRoot, digest)`, which resolves a different level of the layout.

`payloadPage` is the type-keyed form of #2226's `structurePage`; whichever lands second collapses
its own into it so reader and writer resolve a page through one method and the layout cannot
drift. `ArtifactPath` stays in `text.indexing.elasticsearch` despite the leftover package name, to
avoid churning the parallel session's branch. `datashare-index` already depends on
`datashare-api`, so taking `ArtifactType` here is free.

Extensions are fixed by type (convention §71): `page` → `txt`; `structure` → `md`, `xhtml`. For
byteRanges, ranges are interpreted against `content.<requested extension>`; a missing file for the
requested extension is a 404.

## Component 4: `ArtifactType.PAGE`

One constant, `PAGE("page")`, needed to route the path segment. `ArtifactRegistry.select` builds
from registered producers, so `--artifacts=page` still fails with "no producer registered" until
#2227 lands — accurate, not a silent no-op.

## Component 5: `DocumentSourceAccess` (datashare-app, `utils`)

`@Singleton`, beside `DocumentVerifier` and `PayloadFormatter`, injected with `Repository`,
`Indexer`, `PropertiesProvider`:

```java
public Payload gated(String project, String id, String routing, Context context, Function<Document, Payload> whenAllowed)
public Payload source(Document document, String project, boolean inline, boolean filterMetadata)
```

`gated` is `DocumentResource.java:138-151` moved verbatim; `source` is `getPayload`
(`:539-559`) moved verbatim, including the `image/ocr-*` correction and the attachment filename
rule. `DocumentResource` keeps all three of its routes and delegates; `ArtifactResource.raw`
calls `gated(...)` then `source(document, project, inline, false)`. The behavioral proof of the
lift is `DocumentResourceTest` passing unchanged.

## Component 6: deprecating `/documents/content/pages`

Implementation untouched. Adds `@Deprecated` on the method, `deprecated = true` plus a pointer to
`/artifacts/page/:id/:page` in the `@Operation` description, and a `Deprecation: true` response
header. `/documents/pages` and `/documents/src` get nothing.

## Cross-PR coupling with #2226/#2227

Three touchpoints, all with the parallel session, none blocking:

1. **`ArtifactPath`**: they add `structure*`, this branch adds `pages*` + the type-keyed forms.
   Whoever lands second collapses the duplicate. Textual conflict in one file.
2. **Manifest shape**: their Component 4 renames `ManifestEntry.pagination` → `pages` and
   `Pagination.byteRanges` → `ranges`, and keeps `ManifestEntry.total()`. Every read of that shape
   lives in `ArtifactReader.page` and `ArtifactReader.servableEntry` — nowhere in the resource, so
   the rebase is a handful of lines in one file. Test fixtures are the second touchpoint: the
   hand-written manifests move from `"pagination": {...}` to `"pages": {...}` at the same time.
3. **`ArtifactType.PAGE`**: this branch adds it; #2227 must not add it again.

Tell the parallel session to consume `payloadPage` / `payloadContent` rather than formatting
`page-%d` inline.

## Tests

`ArtifactResourceTest extends AbstractProdWebServerTest` with `MockIndexer` and a
`TemporaryFolder` artifactDir set through `ARTIFACT_DIR_OPT`; fixtures are hand-written
`manifest.json` files plus payload files, so nothing waits on a producer:

- **Gate**: ungranted project → 403; unknown document → 404; `artifactDir` unset → 404; another
  project's document → 404.
- **Entry state**: no manifest → 404; manifest without the type key → 404; entry with `EMPTY` →
  404; entry with no `status` → 404.
- **`page`**: `{"pages": 3}`; pages 1..3 return their text; `0`, `4`, and `abc` → 404; filesystem
  and byteRanges fixtures both serve identical text for the same page; a byteRanges fixture whose
  last range runs past EOF → 404.
- **`structure`**: `{"pages": 2, "formats": ["md", "xhtml"]}`; md-only fixture reports
  `["md"]`; `format=md` returns `text/markdown`; `format=xhtml` returns
  `application/xhtml+xml` with the CSP and nosniff headers; `format=pdf` → 400 naming md and
  xhtml; a byteRanges fixture with `content.md` only reports `["md"]`; a manifest claiming 3 pages
  with only 2 files on disk → 404 on page 3 while `{"pages": 3}` still reports 3 (decision 10).
- **`raw`**: bytes plus content type; attachment by default and inline with `inline=true`; root
  document over the limit → 413; download-restricted client address → 403.

`ArtifactReaderTest` (datashare-index) unit-tests scheme resolution, bounds, contiguity, missing
files, and format probing directly. `ArtifactPathTest` gains cases for the new helpers.
`DocumentResourceTest` gains one assertion for the `Deprecation` header and is otherwise unchanged
— its passing is what verifies Component 5.

TDD order: `ArtifactPath` helpers → `ArtifactReader` → `DocumentSourceAccess` lift (existing
`DocumentResourceTest` green) → resource routes, type by type.

## Verification

```
mvn -pl datashare-index -am test -Dtest=ArtifactReaderTest,ArtifactPathTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl datashare-app -am test -Dtest=ArtifactResourceTest,DocumentResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Follow-ups

- Client-side sanitization of served XHTML, and converging datashare-python's docling `structure`
  payload onto the convention (decision 7's residual risk).
- Back `/documents/pages` with the `page` artifact once #2227 guarantees offsets consistent with
  ES `content` (decision 4).
- Migrate `/documents/src` to `/artifacts/raw` with client coordination, and add `HEAD` on
  `/artifacts/raw` mirroring `/documents/src`.
- Whole-manifest endpoint and `thumbnail`, when a consumer exists.
- Update the convention doc's §Serving with the `formats` probing rule, the 400-on-bad-format
  behavior, and the deprecation state of `/documents/content/pages`.
