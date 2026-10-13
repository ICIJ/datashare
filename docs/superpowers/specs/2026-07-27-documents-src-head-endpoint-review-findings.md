# Final whole-branch review: `feat/embedded-download-head-endpoint` (f3feeb897..0564c164b)

Reviewer: final-review subagent. Read-only review of the worktree at
`.claude/worktrees/feat+embedded-download-head-endpoint`, HEAD `0564c164b`,
working tree clean. Test suites were not re-run (results taken from the lead's independent confirmation:
`DocumentResourceTest` 69/69, `DocumentVerifierTest` 7/7, `SourceExtractorTest` 21/21, batch-download 29/29,
against an 85-test green baseline).

Beyond the diff, I read: `DocumentVerifier.java`, `SourceExtractor.java`, `DocumentResource.java` (whole file,
including `getPayload`), `ArtifactPath.java`, `RawArtifact.java`, `BatchDownloadRunner.java` (gate call site and
`Zipper.add`), `CsrfFilter.java`, `DocumentResourceTest` (pre-existing source-file tests), `SourceExtractorTest`,
and decompiled `extract-lib 9.15.6` (`EmbeddedDocumentExtractor.extract`, `EmbeddedArtifactWriter`,
`ArtifactUtils`) plus `fluent-http 5ee80de` (`RouteWithPattern.matchMethod`, `UriParser`, `RouteSorter`,
`RouteCollection.apply`).

## 1. Verdict

**Ready to merge: yes.** No Critical and no Important findings. The branch does exactly what the spec says, in
the smallest place that fixes both callers, and the two claims the whole design rests on (extract-lib's cache-hit
predicate, fluent-http's HEAD-onto-GET fallback and route ordering) hold in the shipped dependency bytecode. The
five Minor findings below are all safe to land as-is; two of them are worth a follow-up line of code, none blocks.

### Independent verification of the two load-bearing claims

Both were re-derived from the jars rather than trusted:

- **extract-lib cache hit predicate.** `EmbeddedDocumentExtractor.extract(root, id)` (9.15.6) does exactly
  `artifactPath != null && getEmbeddedPath(artifactPath, id).toFile().exists()`, where `getEmbeddedPath` delegates
  to `EmbeddedArtifactWriter.rawPath` = `<id[0:2]>/<id[2:4]>/<id>` + `resolve("raw")`. So
  `SourceExtractor.hasCachedEmbeddedSource` (`datashare-index/.../SourceExtractor.java:177-180`) tests the same
  file extract-lib tests, via `ArtifactPath.dir`, which mirrors the same layout
  (`datashare-index/.../ArtifactPath.java:12-14`). `ArtifactUtils.getEmbeddedPath` is indeed package private, so
  the spec is right that it cannot be reused.
- **Route precedence.** `RouteWithPattern.matchMethod` returns true when the request method is HEAD and the
  route's method is GET, confirming a HEAD used to run the entire GET handler. `RouteSorter.getSortedRoutes` sorts
  user routes *only* by `UriParser.compareTo`, ignoring the HTTP method, and `UriParser.compareTo` compares
  `paramsCount` first, where `paramsCount` counts path *and* query params. HEAD (3) therefore sorts before GET (4),
  and `RouteCollection.apply` takes the first route matching uri + method. The precedence in
  `DocumentResource.java:125-128` is real, and the comment's warning is exactly right: at equal param counts,
  `compareTo` falls through to identical pattern parts, returns 0, and the winner is decided by `HashMap.values()`
  iteration order, which is not something to leave to chance.

### Strengths

- The fix is in the one place that covers both download paths. `DocumentVerifier.isRootDocumentSizeAllowed` is
  the single gate (`DocumentResource.sourceFile:145` and `BatchDownloadRunner:138`), and `BatchDownloadRunner` was
  correctly left untouched. `Zipper.add` serves with `doc.getProject()`
  (`BatchDownloadRunner.java:246`), the same project the gate consults, so the batch path's gate and its serving
  path cannot disagree.
- Check order is right and cheap: the cache probe sits *after* the root short-circuit and *before* the ES lookup
  (`DocumentVerifier.java:43-53`), and `DocumentVerifierTest` pins that ordering with
  `verify(indexer, never()).get(...)` rather than only asserting the boolean. That test would still catch a future
  reordering that merely made the gate slower.
- The 413 test asserts GET and HEAD agree, and `test_head_source_root_too_big_with_cached_raw_artifact_should_respond_200`
  is a genuine trip-wire, not a happy-path duplicate: because the fixture root "bar" has no readable path, a
  regression in route precedence turns that 200 into a 404 from failed extraction. The comment above the test says
  so, which is what stops a future reader from "fixing" the fixture and silently disarming it.
- `Files.exists` over the manifest was the correct call, and for a reason the spec states precisely: the streaming
  spew writes raw bytes with no datashare manifest entry, so `FilesystemManifestRepository.get` would answer "no
  artifact" for bytes that are on disk.
- No new abstraction, no new dependency, no signature churn. `DocumentVerifier(Indexer, PropertiesProvider)` is
  unchanged, so both call sites compiled untouched. `SourceExtractor` is stateless for this method and thread safe.
- Comments explain why, not what, and land above the code they justify, matching the surrounding style
  (`useOcr`'s comment right above).

## 2. Findings

### Critical

None.

### Important

None.

### Minor

**M1. The gate can say "allowed" for a `raw` file whose `raw.json` sidecar is missing, and then GET does the full
root parse the limit exists to prevent.**
`datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/SourceExtractor.java:177-180`

`EmbeddedArtifactWriter.write` atomic-moves `raw` into place *first* (bytecode offset 104) and the `raw.json`
sidecar *second* (offset 114). On a cache read, extract-lib checks only `raw`'s existence but then calls
`MetadataTransformer.loadMetadata(<raw>.json)`; if that throws `IOException` it logs
`"cache read failed for embedded document <{}>; falling back to live parse"` at DEBUG and re-parses the root.

Failure scenario: an ARTIFACT worker is SIGKILLed or OOM-killed between the two atomic moves (this repo's own
history has OOM kills mid-spew on giant OSTs), or an `artifactDir` is copied/rsynced partially between boxes. The
affected document then has `raw` without `raw.json`. HEAD answers 200 and the gate permits the download, and the
subsequent GET re-parses a 2 GiB OST, at DEBUG, which is precisely the cost `--embeddedDocumentDownloadMaxSize`
was added to avoid. Blast radius is one document per interrupted write, and it self-heals on the next ARTIFACT
run because `RawArtifact.produce` records the manifest entry only after the write.

Optional one-line hardening, which makes the predicate exactly extract-lib's cache-hit condition instead of
nearly it:

```java
Path dir = ArtifactPath.dir(artifactPath, document.getId());
return artifactPath != null && Files.exists(dir.resolve("raw")) && Files.exists(dir.resolve("raw.json"));
```

The spec's sentence "so `Files.exists(raw)` means complete, readable bytes: there is no partial-file window to
guard against" is true of the payload bytes, and only slightly overstated once the sidecar is in the picture. Fine
to leave; worth a follow-up.

**M2. The new GET 404 (previously 500) is asserted only through HEAD.**
`datashare-app/src/test/java/org/icij/datashare/web/DocumentResourceTest.java:696-699`

The spec's testing section claims "GET and HEAD agree on the refusals (413, 403, 404) for the same document". The
413 test does assert both verbs, and GET 403 is covered by the pre-existing `test_get_source_file_forbidden_index`,
but nothing asserts `GET /api/local-datashare/documents/src/unknown_id` returns 404, which is the one branch this
branch *changes* for GET (`notFoundIfNull` at `DocumentResource.java:144`; previously the null document walked into
`document.isRootDocument()` and produced a 500). The shared method means the HEAD test does exercise the same line,
so the residual risk is a future refactor that splits the verbs again. One line in the existing test closes it,
mirroring the 413 test:

```java
get("/api/local-datashare/documents/src/unknown_id").should().respond(404);
```

**M3. `"raw"` is now a bare literal in two places in `datashare-index`, with no compile-time link between them.**
`SourceExtractor.java:179` and `datashare-index/src/main/java/org/icij/datashare/text/artifact/RawArtifact.java:44`

The payload filename is owned by extract-lib (`EmbeddedArtifactWriter.rawPath`). If it is ever renamed there, the
gate silently starts reporting "no artifact" (users get 413 for embeds whose bytes are cached) while
`RawArtifact.produce` starts throwing "raw extraction produced no bytes" for every embedded document. Two edits,
zero compile errors, two very different symptoms. A `public static final String RAW_FILE = "raw"` next to
`ArtifactPath.MANIFEST_FILE` (`ArtifactPath.java:7`) would keep the extract-lib-owned name in one place.
`ArtifactType.RAW.token()` also equals `"raw"` but means the manifest token, not the filename, so reusing it would
conflate two things that only happen to coincide. Fine to leave; the literal was already there before this branch.

**M4. No length guard on the document id means a sub-4-character embedded id throws instead of being refused, and
in batch download that aborts the whole zip.**
`SourceExtractor.java:179` via `ArtifactPath.dir` (`digest.substring(0, 2)`, `substring(2, 4)`)

This was decided knowingly in the spec and the reasoning holds: real ids are 32 to 96 hex chars, and on the HTTP
path the 404 branch runs first. One consequence is worth recording rather than fixing. `BatchDownloadRunner:138`
calls the gate with no per-document try/catch (only `Zipper.add` has one, for `ContentNotFoundException` and
friends), so a `StringIndexOutOfBoundsException` from a malformed embedded id would abort the entire batch download
instead of dropping one document, and only when `--artifactDir` is set. Requires a hand-crafted or legacy index to
trigger, so no guard is the right call; the note is here so the tradeoff is not rediscovered from a stack trace.

**M5. The gate keys the artifact lookup on `document.getProject()` while GET serves from `project(index)`.**
`DocumentVerifier.java:48` vs `DocumentResource.java:539`

If a document's indexed `project` field ever disagreed with the index it lives in, the gate would probe one
project's artifact dir and the serve would read another's, so a permitted download could still fall back to
extraction (or a refused one be servable). In Datashare index name equals project name and `Zipper.add` already
uses `doc.getProject()`, so this is theoretical. Not worth code; noted for completeness.

### Non-findings, checked and dismissed

- **HEAD returning `Content-Length: 0`.** Confirmed to come from `PayloadWriter`'s null-content branch, not from
  anything the code could avoid while using `ok()`. Harmless for a boolean probe, and documented in the spec.
- **CSRF and auth in production.** `CsrfFilter:23-30` treats HEAD as a safe method alongside GET and OPTIONS, so
  the new route works in prod without a token. `OAuth2CookieFilter`'s method checks are scoped to the signin and
  signout paths.
- **`@Head` support.** `IndexResource.esHead` already ships a `@Head` route, so registration via
  `AnnotationHelper` and swagger generation are established, not new ground.
- **`getSourceFile` still declares `throws IOException`** although `sourceFile` throws nothing checked
  (`getPayload` catches `FileNotFoundException` and `ContentNotFoundException` itself, which is also why the
  `Function<Document, Payload>` lambda compiles). Keeping the declaration costs nothing and preserves the
  signature; removing it would be churn.
- **Extra `stat` per embedded document in batch download.** On a cache hit the stat *replaces* an Elasticsearch
  `get` for the root, so the change is neutral to favourable even with `artifactDir` on EFS. On a miss it adds one
  stat next to an ES round trip.
- **Duplicated `DEFAULT_MAX_SIZE = "1G"`** in `DocumentVerifier.java:18` alongside
  `DatashareCliOptions.DEFAULT_EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE`. Pre-existing, untouched, out of scope.
- **Commit hygiene.** Four semantic messages, no bodies, no co-author trailers, nothing under `docs/superpowers/`
  or `.superpowers/` staged. The only untracked file in the tree (`FIX-ost-zipbomb-deploy.md`) predates the branch.

## 3. Triage of the deferred minor findings in the ledger

| # | Ledger item | Call | Reason |
|---|---|---|---|
| 1 | Task 1: `hasCachedEmbeddedSource` comment is 4 lines | Fine to leave | It matches the neighbouring `useOcr` comment exactly in length and register, and every line earns its place (what, why the size rule can be skipped, why existence is sufficient). |
| 2 | Task 1: `test_..._false_when_dir_exists_without_raw_file` comment says "manifest" but the test creates only the dir | Fine to leave | The assertion is correct and the comment's point (a doc dir can exist with no payload in it) is true; at most a two-word reword, `SourceExtractorTest.java:419`. |
| 3 | Task 2: `isRootDocumentSizeAllowed` javadoc still describes only the size check | Fine to leave, but the one I would actually fix | `DocumentVerifier.java:36-41` is the published contract for a public method whose behaviour just widened, and "@return true if the root document size is allowed" is now wrong by omission; the inline comment carries the why but javadoc readers never see it. One extra sentence ("or if the embedded document's raw artifact is already cached, in which case the root is never opened") settles it. Not a merge blocker. |
| 4 | Task 3: report phrasing credits `ok()` rather than `PayloadWriter` for `Content-Length: 0` | Fine to leave | Report prose only; the spec at line 156-162 already states it correctly, and no code or comment repeats the error. |

Nothing in the deferred list is must-fix before merge.

## 4. Spec and plan claims versus the code

Everything material is delivered. The gaps are documentation-grade:

- **Delivered as specified:** the artifact-aware gate with the exact check order and cost profile from spec
  section 1; the shared `sourceFile` decision method and the 403/404/413/200 ladder from section 2; the `@Head`
  route with the documented param-count precedence; all three test groups at the counts the plan gives (3 + 3 + 6);
  `BatchDownloadRunner` untouched and fixed for free; `ArtifactPath` reused rather than the layout re-hand-rolled;
  no length guard, as instructed.
- **Slightly overstated (M1):** "`Files.exists(raw)` means complete, readable bytes: there is no partial-file
  window to guard against" is true of `raw` itself, but the `raw.json` sidecar lands after it, and a sidecar-less
  `raw` sends extract-lib back to a live parse. So the stronger reading of "serving it never opens the root" is not
  quite unconditional.
- **Claimed test coverage not fully asserted (M2):** "GET and HEAD agree on the refusals (413, 403, 404)" holds
  for 413 in one test and for 403 across two tests, but no test asserts GET's 404 on an unknown id.
- **Corrections the ledger records are genuinely reflected in the code**, not just in the prose: the HEAD-onto-GET
  fallback is now a comment above the route (`DocumentResource.java:125-127`) and a trip-wire test, and the
  `Content-Length: 0` correction is in the spec at the right level of precision.
- **Out-of-scope boundaries respected:** no frontend change, no change to artifact production, no batch-download
  edit beyond what the shared gate gives it.

## 5. Recommendations

1. Land as-is. If you want two cheap follow-ups in this branch or the next: the `raw.json` presence check (M1) and
   the one-line GET 404 assertion (M2).
2. When the frontend half lands, have `isRootTooBig` treat any non-200 from HEAD as "cannot download" rather than
   switching on 413 specifically, so 403 and 404 do not read as "too big".
3. If a query param is ever added to the HEAD route, the regression test that protects precedence is
   `test_head_source_root_too_big_with_cached_raw_artifact_should_respond_200`. It fails with 404, not with a
   routing error, so the comment at `DocumentResource.java:125-127` is the only thing that will explain why.
