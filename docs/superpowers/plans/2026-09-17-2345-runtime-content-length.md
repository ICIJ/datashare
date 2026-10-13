# Runtime Content-Length (#2345) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `DocumentSourceAccess.source()` sends a Content-Length computed from the bytes it is about to serve instead of the indexed `contentLength`.

**Architecture:** Two changes on the existing serve fork. `SourceExtractor` (datashare-index) gains `cachedEmbeddedSourceLength()`, the length-returning form of `hasCachedEmbeddedSource()`. `DocumentSourceAccess.source()` (datashare-app) resolves the served length: root document = file size on disk (with a WARN when it differs from the indexed value), embedded with a cached raw artifact = the raw file's size, embedded without cache = the indexed value. `filter_metadata=true` keeps sending no header.

**Tech Stack:** Java 17, Maven multi-module, JUnit 4 + fest-assert, fluent-http test server (`AbstractProdWebServerTest`), Mockito.

**Spec:** No spec file (bounded task). Requirements: https://github.com/ICIJ/datashare/issues/2345 plus the approved in-chat design (this plan's Goal/Architecture restate both in full).

## Global Constraints

- Branch: `feat/2345-runtime-content-length` off `main`. Create it before the first commit.
- Commit messages: semantic prefix (`feat(index):`, `feat(web):`), no AI co-author trailers, no "Generated with" lines.
- Zero new comments beyond the ones shown verbatim in the code blocks below. Never leave `ponytail:` comments.
- The indexed `contentLength` field itself is untouched (the client aggregates it); only the HTTP header changes.
- Tests need the devenv services: Elasticsearch at `elasticsearch:9200` (the `SourceExtractorTest` class contains ES-backed tests). Run Maven in the background and poll; never block on it.
- `-Dtest` lists use commas, never `+`. Keep `-am` and `-Dsurefire.failIfNoSpecifiedTests=false` in every test command.
- Do not commit this plan file or anything under `docs/superpowers/`.

---

### Task 1: `SourceExtractor.cachedEmbeddedSourceLength()`

**Files:**
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/SourceExtractor.java:194-208`
- Test: `datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/SourceExtractorTest.java`

**Interfaces:**
- Consumes: `ArtifactPath.dir(Path, String)`, `ArtifactPath.RAW_FILE`, `ArtifactPath.RAW_SIDECAR_FILE` (all existing).
- Produces: `public long cachedEmbeddedSourceLength(Project project, Document document)` — byte count of the cached raw artifact, or `-1` when there is no usable cache entry (no artifactDir configured, missing/unreadable `raw` or `raw.json`, or zero-byte `raw`). `hasCachedEmbeddedSource(Project, Document)` keeps its exact signature and semantics (`true` iff length > 0). Task 2 calls `cachedEmbeddedSourceLength`.

- [ ] **Step 1: Create the branch**

```bash
git checkout main && git checkout -b feat/2345-runtime-content-length
```

- [ ] **Step 2: Write the failing tests**

Append inside the class in `SourceExtractorTest.java` (before the closing brace, after `test_has_cached_embedded_source_false_when_no_artifact_dir_configured`). The fixture mirrors `test_has_cached_embedded_source_true_when_raw_file_exists` at `SourceExtractorTest.java:443`; `"embedded bytes"` is 14 bytes:

```java
@Test
public void test_cached_embedded_source_length_is_the_raw_file_byte_count() throws Exception {
    String embeddedId = "a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2";
    File artifactDir = tmpDir.newFolder("artifacts_length");
    Path rawFile = artifactDir.toPath().resolve("prj").resolve("a1").resolve("b2").resolve(embeddedId).resolve("raw");
    Files.createDirectories(rawFile.getParent());
    Files.write(rawFile, "embedded bytes".getBytes());
    Files.write(rawFile.resolveSibling("raw.json"), "{}".getBytes());
    Document embedded = DocumentBuilder.createDoc(embeddedId).with(project("prj")).build();

    SourceExtractor extractor = new SourceExtractor(new PropertiesProvider(Map.of(ARTIFACT_DIR_OPT, artifactDir.toString())));

    assertThat(extractor.cachedEmbeddedSourceLength(project("prj"), embedded)).isEqualTo(14L);
}

@Test
public void test_cached_embedded_source_length_minus_one_when_not_cached() {
    String embeddedId = "a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2";
    Document embedded = DocumentBuilder.createDoc(embeddedId).with(project("prj")).build();

    SourceExtractor extractor = new SourceExtractor(new PropertiesProvider(Map.of()));

    assertThat(extractor.cachedEmbeddedSourceLength(project("prj"), embedded)).isEqualTo(-1L);
}
```

All imports (`File`, `Path`, `Files`, `Map`, `DocumentBuilder`, `project`, `ARTIFACT_DIR_OPT`) already exist in the test file.

- [ ] **Step 3: Run the new tests to verify they fail**

```bash
mvn -pl datashare-index -am test -Dtest=SourceExtractorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Run in the background, poll the output. Expected: COMPILATION ERROR — `cannot find symbol: method cachedEmbeddedSourceLength`.

- [ ] **Step 4: Implement**

In `SourceExtractor.java`, replace the whole `hasCachedEmbeddedSource` method (lines 194-208) with:

```java
    public boolean hasCachedEmbeddedSource(final Project project, final Document document) {
        return cachedEmbeddedSourceLength(project, document) > 0;
    }

    /** Byte count of the cached raw artifact for an embedded document, or -1 when there is no
     *  usable cache entry. extract-lib writes the raw payload uncompressed, so this is exactly
     *  the number of bytes a cache-hit read serves. */
    public long cachedEmbeddedSourceLength(final Project project, final Document document) {
        Path artifactPath = getArtifactPath(project);
        if (artifactPath == null) {
            return -1;
        }
        Path documentArtifactDir = ArtifactPath.dir(artifactPath, document.getId());
        Path rawPayload = documentArtifactDir.resolve(ArtifactPath.RAW_FILE);
        Path rawSidecar = documentArtifactDir.resolve(ArtifactPath.RAW_SIDECAR_FILE);
        // Readable, not merely present: the files are written 0600 by whichever process produced
        // them, and an unreadable artifact sends extract-lib back to a live parse of the root.
        if (!Files.isReadable(rawPayload) || !Files.isReadable(rawSidecar)) {
            return -1;
        }
        // Non-empty too: extract-lib records a zero-byte payload for embeds whose bytes it could
        // not read, and serving those as a cache hit would return an empty 200 where the size
        // limit used to return 413.
        long length = rawPayload.toFile().length();
        return length > 0 ? length : -1;
    }
```

This moves the two existing comments (they were on the old method body); do not duplicate them elsewhere. No import changes needed.

- [ ] **Step 5: Run the full test class to verify everything passes**

```bash
mvn -pl datashare-index -am test -Dtest=SourceExtractorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Run in the background, poll. Expected: all tests pass, including the five existing `test_has_cached_embedded_source_*` tests (they pin that the refactor preserved semantics).

- [ ] **Step 6: Commit**

```bash
git add datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/SourceExtractor.java datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/SourceExtractorTest.java && git commit -m "feat(index): expose the cached raw artifact's byte count

hasCachedEmbeddedSource becomes the boolean view of the new
cachedEmbeddedSourceLength, so the served Content-Length can be
resolved from the same cache probe (#2345)."
```

---

### Task 2: `DocumentSourceAccess.source()` resolves the served length

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/utils/DocumentSourceAccess.java:69-92`
- Test: `datashare-app/src/test/java/org/icij/datashare/web/DocumentResourceTest.java`

**Interfaces:**
- Consumes: `SourceExtractor.cachedEmbeddedSourceLength(Project, Document)` from Task 1; `Document.isRootDocument()`, `Document.getContentLength()` (returns `Long`, the builder maps null to `0L`).
- Produces: no new public API. Behavior change only: the Content-Length header of `GET /api/:project/documents/src/:id` and `GET /api/:project/artifacts/raw/:id`.

- [ ] **Step 1: Write the failing tests**

In `DocumentResourceTest.java`:

**(a)** Add three new tests after `test_get_source_file_without_metadata` (line 126). `"content"` is 7 bytes; `"embedded bytes"` is 14 bytes. The cached-artifact fixture mirrors `test_head_source_root_too_big_with_cached_raw_artifact_should_respond_200` at line 680 (keep root "bar" at 2GB: it also pins that the cache hit bypasses the size gate on GET). The ods content type avoids fluent-http gzip, same reason as the comment at line 90:

```java
@Test
public void test_get_source_file_with_stale_indexed_content_length_serves_disk_size_and_warns() throws Exception {
    File txtFile = new File(temp.getRoot(), "/my/path/to/stale.ods");
    MockIndexer.write(txtFile, "content");
    Document staleDoc = DocumentBuilder.createDoc("id_stale").with(txtFile.toPath()).with(new Project("local-datashare"))
            .ofContentType("application/vnd.oasis.opendocument.spreadsheet").withContentLength(999).build();
    mockIndexer.indexFile("local-datashare", staleDoc);

    get("/api/local-datashare/documents/src/id_stale").should()
            .haveHeader("Content-Length", "7");
    assertThat(logback.logs(Level.WARN))
            .contains("document id_stale changed on disk: indexed contentLength is 999 but serving 7 bytes");
}

@Test
public void test_get_embedded_source_file_with_cached_raw_artifact_serves_the_raw_size() throws Exception {
    String embeddedId = "a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2";
    String path = getClass().getResource("/docs/embedded_doc.eml").getPath();
    Project index = new Project("local-datashare");
    Document documentBar = createDoc("bar").with(index).withContentLength(2L * 1024 * 1024 * 1024).build();
    Document embedded = createDoc(embeddedId).with(index).with(Paths.get(path))
            .ofContentType("application/vnd.oasis.opendocument.spreadsheet")
            .withParentId("bar").withRootId("bar").build();
    mockIndexer.indexFile("local-datashare", documentBar, embedded);
    File artifactDir = temp.newFolder("artifacts_get_raw");
    java.nio.file.Path rawFile = artifactDir.toPath().resolve("local-datashare").resolve("a1").resolve("b2").resolve(embeddedId).resolve("raw");
    Files.createDirectories(rawFile.getParent());
    Files.write(rawFile, "embedded bytes".getBytes());
    Files.write(rawFile.resolveSibling("raw.json"), "{}".getBytes());
    when(propertiesProvider.get(ARTIFACT_DIR_OPT)).thenReturn(Optional.of(artifactDir.toString()));

    get("/api/local-datashare/documents/src/" + embeddedId + "?routing=bar").should()
            .respond(200)
            .haveHeader("Content-Length", "14");
    // The mismatch warning is for roots only: a Tika-declared embed size legitimately
    // differs from the cached bytes, so no WARN may fire here.
    assertThat(logback.logs(Level.WARN).stream().anyMatch(log -> log.contains("changed on disk"))).isFalse();
}

@Test
public void test_get_embedded_source_file_without_cached_artifact_keeps_the_indexed_length() {
    // MockIndexer stamps contentLength=10 on the embed; no artifact dir is configured, so the
    // live-parse path must keep sending the indexed value even though the served PDF is bigger.
    String path = getClass().getResource("/docs/embedded_doc.eml").getPath();
    mockIndexer.indexFile("local-datashare", "d365f488df3c84ecd6d7aa752ca268b78589f2082e4fe2fbe9f62dff6b3a6b74bedc645ec6df9ae5599dab7631433623", Paths.get(path), "application/pdf", "id_eml");

    get("/api/local-datashare/documents/src/d365f488df3c84ecd6d7aa752ca268b78589f2082e4fe2fbe9f62dff6b3a6b74bedc645ec6df9ae5599dab7631433623?routing=id_eml")
            .should().respond(200).haveHeader("Content-Length", "10");
}
```

New imports are NOT needed: `File`, `Files`, `Paths`, `Optional`, `Document`, `DocumentBuilder`, `Project`, `createDoc`, `ARTIFACT_DIR_OPT`, `Level`, `assertThat`, `when` are all already imported in this test file (see lines 1-55).

**(b)** Update `test_get_source_file_without_indexed_content_length` (lines 103-115): the fixture is a ROOT document with no indexed length, and a root's length now comes from disk. Replace the whole test with:

```java
@Test
public void test_get_root_source_file_without_indexed_content_length_serves_the_disk_size() throws Exception {
    // Roots indexed without a contentLength (legacy indexes) used to get no header at all:
    // the disk size is now the source of truth for a root, so the header is served anyway.
    File txtFile = new File(temp.getRoot(), "/my/path/to/file.ods");
    MockIndexer.write(txtFile, "content");
    Document docWithoutContentLength = DocumentBuilder.createDoc().withId("id_ods_no_length").with(txtFile.toPath()).with(new Project("local-datashare"))
            .ofContentType("application/vnd.oasis.opendocument.spreadsheet").build();
    mockIndexer.indexFile("local-datashare", docWithoutContentLength);

    get("/api/local-datashare/documents/src/id_ods_no_length").should()
            .haveHeader("Content-Length", "7");
}
```

The embed-side "no indexed length means no header" behavior that test's comment described is preserved by the `> 0` guard and now documented by the header-null assertion already present in `test_get_source_file_without_metadata`.

- [ ] **Step 2: Run the test class to verify the new tests fail**

```bash
mvn -pl datashare-app -am test -Dtest=DocumentResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Run in the background, poll. Expected failures, everything else green:
- `test_get_source_file_with_stale_indexed_content_length_serves_disk_size_and_warns`: header is "999", not "7".
- `test_get_embedded_source_file_with_cached_raw_artifact_serves_the_raw_size`: no Content-Length header (indexed length is 0).
- `test_get_root_source_file_without_indexed_content_length_serves_the_disk_size`: no Content-Length header.
- `test_get_embedded_source_file_without_cached_artifact_keeps_the_indexed_length` passes already (it pins current behavior against regression); that is expected.

- [ ] **Step 3: Implement**

In `DocumentSourceAccess.java`, replace the `source` method (lines 69-92) with:

```java
    public Payload source(Document doc, String index, boolean inline, boolean filterMetadata) {
        try {
            SourceExtractor sources = new SourceExtractor(propertiesProvider, filterMetadata);
            InputStream from = sources.getSource(project(index), doc);
            String contentType =
                    ofNullable(doc.getContentType()).orElse(ContentTypes.get(doc.getPath().toFile().getName()));
            // OCR-routed embedded images are stored with a synthetic "image/ocr-<fmt>" content type
            // (the "ocr-" prefix routes them through the OCR parser). Serve the real media type so the
            // Content-Type header and the download filename extension are correct (otherwise ".bin").
            if (contentType != null && contentType.startsWith("image/ocr-")) {
                contentType = "image/" + contentType.substring("image/ocr-".length());
            }
            Payload payload = new Payload(contentType, from);
            // filter_metadata rewrites the payload, so the on-disk byte count is wrong for it:
            // send no header at all rather than a length the response will not honor.
            if (!filterMetadata) {
                long contentLength = servedContentLength(sources, doc, project(index));
                if (contentLength > 0) {
                    payload.withHeader(CONTENT_LENGTH, String.valueOf(contentLength));
                }
            }
            String fileName = doc.isRootDocument() ? doc.getName() :
                              doc.getId().substring(0, 10) + "." + FileExtension.get(contentType);
            return inline ? payload :
                   payload.withHeader("Content-Disposition", "attachment;filename=\"" + fileName + "\"");
        } catch (FileNotFoundException | EmbeddedDocumentExtractor.ContentNotFoundException fnf) {
            logger.error("unable to read document source file", fnf);
            return Payload.notFound();
        }
    }

    // The length of the bytes about to be served, not the indexed contentLength: the indexed
    // value goes stale when a root file changes on disk and is usually absent for embedded
    // documents. Same fork as the download gate (root / cached artifact / live parse), one
    // stat on a path the request just opened.
    private long servedContentLength(SourceExtractor sources, Document doc, Project servingProject) {
        if (!doc.isRootDocument()) {
            long cachedLength = sources.cachedEmbeddedSourceLength(servingProject, doc);
            return cachedLength > 0 ? cachedLength : doc.getContentLength();
        }
        try {
            long diskLength = Files.size(doc.getPath());
            // Tika indexed Files.size at extraction time, so a mismatch means the file changed
            // under us and is worth a manual look. Roots only: for embeds, a Tika-declared size
            // can legitimately differ from the bytes we cached, comparing would just be noise.
            if (diskLength != doc.getContentLength()) {
                logger.warn("document {} changed on disk: indexed contentLength is {} but serving {} bytes",
                            doc.getId(), doc.getContentLength(), diskLength);
            }
            return diskLength;
        } catch (IOException e) {
            return doc.getContentLength();
        }
    }
```

Add two imports next to the existing `java.io` / `java.util` block:

```java
import java.io.IOException;
import java.nio.file.Files;
```

Note the resolution order stays "stream first, length second": for a live-parsed embed, `getSource` may have just written the artifact cache, but the header must describe the stream actually returned, and the `cachedLength > 0 : indexed` fallback is correct either way.

- [ ] **Step 4: Run the test class to verify everything passes**

```bash
mvn -pl datashare-app -am test -Dtest=DocumentResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Run in the background, poll. Expected: all pass. `test_get_source_file` and `test_get_source_file_with_content_type` now also exercise the disk-size path (their files match or the WARN is harmless); if `test_get_source_file_with_content_type` fails, its file really is 7 bytes ("content") and indexed 7, so investigate rather than patch the assertion.

- [ ] **Step 5: Run the sibling consumers of `source()`**

`ArtifactResource` (`/artifacts/raw`) and `TabularRowReader` route through the same method:

```bash
mvn -pl datashare-app -am test -Dtest=ArtifactResourceTest,DocumentSourceAccessTest,DocumentVerifierTest -Dsurefire.failIfNoSpecifiedTests=false
```

Run in the background, poll (skip test classes in that list that don't exist; the flag makes that safe). Expected: all pass — raw-route fixtures serve small on-disk roots whose disk size is what gets served.

- [ ] **Step 6: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/utils/DocumentSourceAccess.java datashare-app/src/test/java/org/icij/datashare/web/DocumentResourceTest.java && git commit -m "feat(web): compute the served Content-Length at runtime

The indexed contentLength goes stale when a root file changes on disk
(the browser aborts on the byte-count mismatch) and is absent for
embedded documents. Resolve the header from the bytes actually served:
disk size for a root (warning when it differs from the indexed value),
raw artifact size for a cached embed, indexed value otherwise.
filter_metadata keeps sending no header (#2345)."
```

---

## Verification (after both tasks)

- [ ] Full modules in the background, poll until done:

```bash
mvn -pl datashare-index,datashare-app -am test
```

Known env-dependent failures on any branch (do not chase): the 4 classes recorded in the local-test-gotchas note. Everything else must be green before calling the branch done.
