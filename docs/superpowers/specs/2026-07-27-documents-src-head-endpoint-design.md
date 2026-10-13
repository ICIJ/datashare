# HEAD /:project/documents/src/:id and artifact-aware download gate

Date: 2026-07-27
Issue: [#2287](https://github.com/ICIJ/datashare/issues/2287) (backend half)

## Problem

Datashare refuses to serve an embedded document when its root document is bigger than
`--embeddedDocumentDownloadMaxSize` (default `1G`). The limit exists because serving an embedded
document normally means re-parsing the whole root on demand, which is slow and memory hungry on
huge roots (OST, PST, ZIP).

That reasoning does not hold when the embedded document already has a `raw` artifact cached under
`--artifactDir`: the bytes are read straight off disk and the root file is never opened. Today the
user still gets `413 The file or its parent is too large`, which is exactly the case artifacts were
built to fix.

The frontend makes it worse: `useDocumentDownload.isRootTooBig`
(`datashare-client/src/composables/useDocumentDownload.js:61`) recomputes the same rule from
`document.root.contentLength` and the `embeddedDocumentDownloadMaxSize` config, then hides the
download entry (`DocumentActionsGroupEntryDownload.vue:46`) and disables the download button
(`DocumentDownloadPopover.vue:97`). The client cannot see the artifact cache, so fixing only the
serving path would leave the button greyed out over a file the API is now happy to serve.

## Scope

Backend only, one branch:

1. make the download gate artifact aware, so an embedded document with cached `raw` bytes is served
   whatever its root size;
2. expose that decision over `HEAD /api/:project/documents/src/:id`, so the client can ask instead
   of guessing.

The frontend change (deriving `isRootTooBig` from this endpoint) is out of scope here and lands
separately against the same issue.

## Verified facts

These were checked against the code and jars, not assumed:

- The gate is a single method, `DocumentVerifier.isRootDocumentSizeAllowed`
  (`datashare-app/src/main/java/org/icij/datashare/utils/DocumentVerifier.java:39`), with two
  callers: `DocumentResource.getSourceFile` (`:114`, returns 413) and `BatchDownloadRunner`
  (`:138`, silently drops the document from the zip). Fixing the method covers both.
- extract-lib writes the payload as a **file** at
  `artifactDir/<project>/<id[0:2]>/<id[2:4]>/<id>/raw` with a `raw.json` sidecar, both via
  write-to-temp then atomic move (`EmbeddedArtifactWriter`). So `Files.exists(raw)` means complete,
  readable bytes: there is no partial-file window on the payload itself.
- Both files are needed, though, and an earlier draft of this spec missed it.
  `EmbeddedDocumentExtractor.extract` serves from cache only when `raw` exists **and**
  `MetadataTransformer.loadMetadata(raw + ".json")` succeeds; on failure it logs at DEBUG and falls
  through to a live parse of the root. The sidecar is moved into place *after* the payload, so a
  worker killed between the two moves leaves a `raw` file that would not spare us the root parse.
  The gate therefore requires both files, which makes its predicate exactly extract-lib's
  cache-hit condition rather than approximately it.
- extract-lib's own path helper (`ArtifactUtils.getEmbeddedPath`) is package private, so it cannot
  be reused. `ArtifactPath.dir` in datashare-index already mirrors that layout and is the thing to
  use. No new duplication is introduced.
- fluent-http DOES fall a HEAD request back onto a matching `@Get` route:
  `RouteWithPattern.matchMethod` returns true when the request method is HEAD and the route's
  method is GET, and `PayloadWriter` then strips the body. (An earlier draft of this spec claimed
  the opposite, from reading `RouteCollection` instead of `RouteWithPattern`. Corrected during
  implementation, when two of the six planned HEAD tests turned out to pass before the route
  existed.) Two consequences:
  - Today `HEAD /api/:project/documents/src/:id` already answers, by running the entire GET
    handler: it extracts the embedded source, then throws the bytes away. The explicit `@Head`
    route is therefore not only a new capability, it stops that waste. It also explains a real
    pre-existing symptom: a HEAD on an over-limit embedded document was doing full extraction work
    before refusing.
  - Both routes match HEAD for the same path, so precedence matters. `RouteSorter` keeps user
    routes in a HashMap and sorts only by `UriParser.compareTo`, which orders by parameter count
    ascending. The HEAD route declares 3 params against GET's 4 (GET also has `filter_metadata`),
    so HEAD sorts first and wins. GET is never affected, since the fallback is HEAD-to-GET only.
    The behaviour is pinned by the cached-artifact test: if GET's handler served HEAD, that test
    would fail on extraction instead of returning 200.
  `@Head` registers its own route via `AnnotationHelper`, as already done in `IndexResource.esHead`
  (`:103`), and returning `ok()` is body free either way.
- The web test harness supports HEAD: `FluentRestTest.head(String)` is available to
  `AbstractProdWebServerTest` subclasses such as `DocumentResourceTest`.

## Design

### 1. Artifact-aware gate

`SourceExtractor` (datashare-index) owns reading the artifact cache and already computes
`getArtifactPath(project)`. It gains the presence check, so cache-layout knowledge stays in one
place:

```java
public boolean hasCachedEmbeddedSource(final Project project, final Document document) {
    Path artifactPath = getArtifactPath(project);
    return artifactPath != null && Files.exists(ArtifactPath.dir(artifactPath, document.getId()).resolve("raw"));
}
```

`DocumentVerifier` consults it before looking the root up in Elasticsearch, so a cache hit costs one
`stat` and zero ES calls:

```java
public boolean isRootDocumentSizeAllowed(Document document) {
    if (document.isRootDocument()) {
        return true;
    }
    // A cached raw artifact is served straight off disk: the root is never opened, so its size
    // does not matter. This is the case the artifact cache exists for.
    if (sources.hasCachedEmbeddedSource(document.getProject(), document)) {
        return true;
    }
    long maxSizeBytes = getEmbeddedDocumentDownloadMaxSizeBytes();
    Document rootDocument = getRootDocument(document);
    return rootDocument.getContentLength() < maxSizeBytes;
}
```

`DocumentVerifier` builds its own `SourceExtractor(propertiesProvider)` in its constructor:
`SourceExtractor` is stateless and cheap, so the public constructor
`DocumentVerifier(Indexer, PropertiesProvider)` keeps its signature. Both call sites therefore
compile untouched by this part of the change (`DocumentResource` is still edited, but only for the
HEAD refactor in section 2, and `BatchDownloadRunner` is not edited at all).

Rejected alternative: reading the manifest (`FilesystemManifestRepository.get(dir, "raw")`). The
streaming spew writes raw bytes during INDEX without writing a datashare manifest entry, so a
manifest check would report "no artifact" for documents whose bytes are on disk. It is both less
correct and more code than one `Files.exists`.

### 2. One decision, two verbs

`DocumentResource.getSourceFile` keeps its route and signature; its body moves into a private method
that GET and HEAD share, so the two verbs cannot drift apart:

```java
@Head("/:project/documents/src/:id?routing=:routing")
public Payload headSourceFile(final String project, final String id, final String routing, final Context context) {
    return sourceFile(project, id, routing, context, doc -> ok());
}

// getSourceFile body:
return sourceFile(project, id, routing, context,
        doc -> getPayload(doc, project, inline, parseBoolean(filterMetadata)));

// returns the refusal, or whenAllowed.apply(document) when the download is permitted
private Payload sourceFile(String project, String id, String routing, Context context,
                           Function<Document, Payload> whenAllowed)
```

Check order mirrors today's GET:

| Order | Condition | Response |
|---|---|---|
| 1 | project not granted, or download not allowed for the client IP | `403` |
| 2 | no such document | `404` |
| 3 | root over the limit and no cached `raw` artifact | `413` |
| 4 | otherwise | `whenAllowed`: the payload for GET, empty `200` for HEAD |

The `404` is new behaviour: `indexer.get` returns `null` for an unknown id and the current GET walks
straight into `document.isRootDocument()`, so today an unknown id is a `500`. The shared method has
to define that branch anyway, and `notFoundIfNull` is already used by `getDoc` in the same class.

`HEAD` sends no index-derived `Content-Length` and no `Content-Type`. Those values come from the
index and can disagree with the bytes actually served, so the endpoint answers the one question it
can answer truthfully: would `GET` serve this.

Precisely, since an earlier draft of this section overstated it: `ok()` yields a `Payload` with null
content, and `PayloadWriter` handles that branch by calling `setContentLength(0)` and skipping the
`Content-Type` header entirely. So the wire response carries `Content-Length: 0`, which is the
correct framing for an empty body and is not the index-derived value this rule exists to avoid.
It is also not avoidable while using `ok()`, which is what this design specifies. The existing test
helper (`fluent-rest-test`'s `Response`) exposes only `contentType()`, `content()` and `code()`, so
no test asserts on headers either way.

### 3. Cost

HEAD never opens the root and never extracts anything: at worst one `stat` for the artifact plus one
ES `get` for the root document, which is what the client's own heuristic already costs it in
document data. Root documents short-circuit on the existing first branch.

## Testing

- `DocumentVerifierTest` (unit, mocks + `TemporaryFolder` as `--artifactDir`):
  - over-limit root, `raw` file present for the embedded id: allowed;
  - over-limit root, no `raw` file: refused (current behaviour preserved);
  - over-limit root, `--artifactDir` unset: refused;
  - the existing four tests keep passing unchanged: Mockito returns `Optional.empty()` for an
    unstubbed `Optional` getter, so the injected `SourceExtractor` sees no `--artifactDir` and the
    gate falls through to the size comparison those tests assert on.
- `SourceExtractorTest`: `hasCachedEmbeddedSource` true when the `raw` file exists, false when the
  directory exists without it, false when `--artifactDir` is unset.
- `DocumentResourceTest` (`head(...)`):
  - `200` for a root document;
  - `200` for an embedded document over the limit with a cached `raw` artifact;
  - `413` for the same document without the artifact;
  - `404` for an unknown id;
  - `403` when the project is not granted;
  - HEAD response has an empty body while the same GET sends the bytes;
  - GET and HEAD agree on the refusals (`413`, `403`, `404`) for the same document.

The artifact-hit case is asserted over HTTP on HEAD only, plus at unit level in
`DocumentVerifierTest`. Asserting `GET` 200 on a cache hit would drive `EmbeddedDocumentExtractor`
over a fixture root and end up testing extract-lib's cache lookup rather than this change.

## Out of scope

- Frontend: `isRootTooBig` reading this endpoint instead of recomputing the size rule.
- Any change to how the artifacts themselves are produced.
- Batch download behaviour beyond what the shared `DocumentVerifier` fix gives it for free.
