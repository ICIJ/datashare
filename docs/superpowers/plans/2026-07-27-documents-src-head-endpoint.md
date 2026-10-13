# HEAD /:project/documents/src/:id and artifact-aware download gate: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve an embedded document whose `raw` artifact is already cached whatever its root document's size, and expose that decision over `HEAD /api/:project/documents/src/:id` so the frontend can ask instead of recomputing the rule.

**Architecture:** Three layers, bottom up. `SourceExtractor` (datashare-index) answers "are the embedded bytes already on disk?" since it owns the artifact cache. `DocumentVerifier` (datashare-app) consults that before the size comparison, which fixes both download callers at once. `DocumentResource` moves its GET body into one private decision method that GET and HEAD share, so the two verbs cannot drift.

**Tech Stack:** Java 17, Maven multi-module, JUnit 4 + Mockito + fest-assert, fluent-http (`@Get`/`@Head` routes, `FluentRestTest` test client), extract-lib 9.15.6.

**Spec:** `docs/superpowers/specs/2026-07-27-documents-src-head-endpoint-design.md`
**Issue:** [#2287](https://github.com/ICIJ/datashare/issues/2287) (backend half)
**Branch:** `feat/embedded-download-head-endpoint` (already created off `main`)

## Global Constraints

- Commit messages are semantic (`type(scope): description`), no body, no co-author trailers, no "Generated with Claude".
- Never stage anything under `docs/superpowers/` (it is gitignored, `.gitignore:23`) nor any superpowers skill file.
- The artifact cache layout is owned by extract-lib and mirrored by `ArtifactPath` (datashare-index, package `org.icij.datashare.text.indexing.elasticsearch`, same package as `SourceExtractor`, so no import is needed for it). Never hand-roll the `<id[0:2]>/<id[2:4]>/<id>` path anywhere else.
- The payload file is `.../<id>/raw`, written temp-then-atomic-move by extract-lib's `EmbeddedArtifactWriter`, so `Files.exists` on it is sufficient: no partial-file handling.
- Document ids used with the artifact path must be at least 4 characters. Real ids are digests (32 to 96 hex chars); the new tests use a 64-character hex id for that reason. Do not add a length guard.
- Elasticsearch must be reachable at `elasticsearch:9200` for `SourceExtractorTest` (it holds an `ElasticsearchRule` `@ClassRule`), even though the new tests in it do not use ES.
- Single-test command needs the no-op guard: `-Dsurefire.failIfNoSpecifiedTests=false`.
- Keep the existing comment style: short, explaining *why*, above the code it justifies.

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/SourceExtractor.java` | reads the artifact cache and serves embedded sources | add `hasCachedEmbeddedSource` (Task 1) |
| `datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/SourceExtractorTest.java` | its tests | add 3 tests (Task 1) |
| `datashare-app/src/main/java/org/icij/datashare/utils/DocumentVerifier.java` | the single download gate, shared by web + batch download | consult the cache before the size rule (Task 2) |
| `datashare-app/src/test/java/org/icij/datashare/utils/DocumentVerifierTest.java` | its tests | add 3 tests (Task 2) |
| `datashare-app/src/main/java/org/icij/datashare/web/DocumentResource.java` | HTTP routes for documents | extract shared decision method, add `@Head` route (Task 3) |
| `datashare-app/src/test/java/org/icij/datashare/web/DocumentResourceTest.java` | its tests | add 6 HEAD tests (Task 3) |

`BatchDownloadRunner` is deliberately **not** touched: it calls `DocumentVerifier.isRootDocumentSizeAllowed`, so Task 2 fixes it for free.

---

### Task 1: SourceExtractor reports cached raw artifacts

**Files:**
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/SourceExtractor.java` (add method near `getArtifactPath`, line ~172; add one import)
- Test: `datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/SourceExtractorTest.java`

**Interfaces:**
- Consumes: `SourceExtractor.getArtifactPath(Project)` (existing, private, returns `null` when `--artifactDir` is unset); `ArtifactPath.dir(Path projectRoot, String digest)` (existing, public static, same package).
- Produces: `public boolean hasCachedEmbeddedSource(Project project, Document document)` — used by Task 2.

- [ ] **Step 1: Write the failing tests**

Append to `SourceExtractorTest` (the class already has `@Rule public TemporaryFolder tmpDir` and static imports for `ARTIFACT_DIR_OPT` and `project`):

```java
    @Test
    public void test_has_cached_embedded_source_true_when_raw_file_exists() throws Exception {
        String embeddedId = "a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2";
        File artifactDir = tmpDir.newFolder("artifacts");
        // extract-lib's layout: <artifactDir>/<project>/<id[0:2]>/<id[2:4]>/<id>/raw
        Path rawFile = artifactDir.toPath().resolve("prj").resolve("a1").resolve("b2").resolve(embeddedId).resolve("raw");
        Files.createDirectories(rawFile.getParent());
        Files.write(rawFile, "embedded bytes".getBytes());
        Document embedded = DocumentBuilder.createDoc(embeddedId).with(project("prj")).build();

        SourceExtractor extractor = new SourceExtractor(new PropertiesProvider(Map.of(ARTIFACT_DIR_OPT, artifactDir.toString())));

        assertThat(extractor.hasCachedEmbeddedSource(project("prj"), embedded)).isTrue();
    }

    @Test
    public void test_has_cached_embedded_source_false_when_dir_exists_without_raw_file() throws Exception {
        String embeddedId = "a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2";
        File artifactDir = tmpDir.newFolder("artifacts_no_raw");
        // The document dir can exist with only a manifest in it (nothing produced yet).
        Files.createDirectories(artifactDir.toPath().resolve("prj").resolve("a1").resolve("b2").resolve(embeddedId));
        Document embedded = DocumentBuilder.createDoc(embeddedId).with(project("prj")).build();

        SourceExtractor extractor = new SourceExtractor(new PropertiesProvider(Map.of(ARTIFACT_DIR_OPT, artifactDir.toString())));

        assertThat(extractor.hasCachedEmbeddedSource(project("prj"), embedded)).isFalse();
    }

    @Test
    public void test_has_cached_embedded_source_false_when_no_artifact_dir_configured() {
        String embeddedId = "a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2";
        Document embedded = DocumentBuilder.createDoc(embeddedId).with(project("prj")).build();

        SourceExtractor extractor = new SourceExtractor(new PropertiesProvider(Map.of()));

        assertThat(extractor.hasCachedEmbeddedSource(project("prj"), embedded)).isFalse();
    }
```

Add the missing import to the test file (`Path`, `Map`, `File`, `DocumentBuilder`, `PropertiesProvider` are already imported):

```java
import java.nio.file.Files;
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
mvn -pl datashare-index -am test -Dtest=SourceExtractorTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: compilation failure, `cannot find symbol: method hasCachedEmbeddedSource(Project,Document)`.

- [ ] **Step 3: Write the implementation**

In `SourceExtractor`, add the import:

```java
import java.nio.file.Files;
```

and the method, right above the existing `private Path getArtifactPath(Project project)`:

```java
    // Whether the embedded document's bytes are already sitting in the artifact cache. When they
    // are, serving it never opens (nor re-parses) the root document, which is what lets the
    // download gate ignore the root's size. extract-lib writes this payload temp-then-atomic-move,
    // so its mere existence means complete, readable bytes.
    public boolean hasCachedEmbeddedSource(final Project project, final Document document) {
        Path artifactPath = getArtifactPath(project);
        return artifactPath != null && Files.exists(ArtifactPath.dir(artifactPath, document.getId()).resolve("raw"));
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run:
```bash
mvn -pl datashare-index -am test -Dtest=SourceExtractorTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS, including the pre-existing tests in the class (they are untouched).

- [ ] **Step 5: Commit**

```bash
git add datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/SourceExtractor.java \
        datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/SourceExtractorTest.java
git commit -m "feat(download): report cached raw artifact presence from SourceExtractor"
```

---

### Task 2: DocumentVerifier ignores the root size on a cache hit

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/utils/DocumentVerifier.java` (constructor + `isRootDocumentSizeAllowed`, lines 28-46)
- Test: `datashare-app/src/test/java/org/icij/datashare/utils/DocumentVerifierTest.java`

**Interfaces:**
- Consumes: `SourceExtractor.hasCachedEmbeddedSource(Project, Document)` from Task 1; `new SourceExtractor(PropertiesProvider)` (existing public constructor).
- Produces: no signature change. `DocumentVerifier(Indexer, PropertiesProvider)` and `isRootDocumentSizeAllowed(Document)` keep their exact signatures, so `DocumentResource` and `BatchDownloadRunner` still compile untouched.

- [ ] **Step 1: Write the failing tests**

Append to `DocumentVerifierTest`. It mocks `PropertiesProvider`, and Mockito returns `Optional.empty()` for an unstubbed `Optional` getter, so the existing four tests keep seeing no `--artifactDir` and keep asserting the size rule.

```java
    @Test
    public void test_is_root_document_size_allowed_true_for_embedded_doc_with_cached_raw_artifact() throws Exception {
        Project project = new Project("local-datashare");
        String embeddedId = "a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2";
        Document rootDoc = DocumentBuilder.createDoc("bar").with(project).withContentLength(2L * 1024 * 1024 * 1024).build();
        Document doc = DocumentBuilder.createDoc(embeddedId).with(project).withParentId("bar").withRootId("bar").build();
        Path rawFile = artifactDir.getRoot().toPath().resolve("local-datashare").resolve("a1").resolve("b2").resolve(embeddedId).resolve("raw");
        Files.createDirectories(rawFile.getParent());
        Files.write(rawFile, "embedded bytes".getBytes());

        when(indexer.get(project.getId(), "bar")).thenReturn(rootDoc);
        when(propertiesProvider.get(EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT)).thenReturn(Optional.of("1G"));
        when(propertiesProvider.get(ARTIFACT_DIR_OPT)).thenReturn(Optional.of(artifactDir.getRoot().toString()));

        assertTrue(documentVerifier.isRootDocumentSizeAllowed(doc));
        // The cache hit must be checked before the root lookup: a cached embed costs one stat and
        // zero Elasticsearch calls.
        verify(indexer, never()).get(project.getId(), "bar");
    }

    @Test
    public void test_is_root_document_size_allowed_false_for_embedded_doc_without_cached_raw_artifact() {
        Project project = new Project("local-datashare");
        String embeddedId = "a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2";
        Document rootDoc = DocumentBuilder.createDoc("bar").with(project).withContentLength(2L * 1024 * 1024 * 1024).build();
        Document doc = DocumentBuilder.createDoc(embeddedId).with(project).withParentId("bar").withRootId("bar").build();

        when(indexer.get(project.getId(), "bar")).thenReturn(rootDoc);
        when(propertiesProvider.get(EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT)).thenReturn(Optional.of("1G"));
        when(propertiesProvider.get(ARTIFACT_DIR_OPT)).thenReturn(Optional.of(artifactDir.getRoot().toString()));

        assertFalse(documentVerifier.isRootDocumentSizeAllowed(doc));
    }

    @Test
    public void test_is_root_document_size_allowed_false_for_big_root_when_no_artifact_dir_configured() {
        Project project = new Project("local-datashare");
        Document rootDoc = DocumentBuilder.createDoc("bar").with(project).withContentLength(2L * 1024 * 1024 * 1024).build();
        Document doc = DocumentBuilder.createDoc("foo").with(project).withParentId("bar").withRootId("bar").build();

        when(indexer.get(project.getId(), "bar")).thenReturn(rootDoc);
        when(propertiesProvider.get(EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT)).thenReturn(Optional.of("1G"));

        assertFalse(documentVerifier.isRootDocumentSizeAllowed(doc));
    }
```

Add to the same test class, next to the existing fields:

```java
    @Rule public TemporaryFolder artifactDir = new TemporaryFolder();
```

and the imports it needs:

```java
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACT_DIR_OPT;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
mvn -pl datashare-app -am test -Dtest=DocumentVerifierTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: `test_is_root_document_size_allowed_true_for_embedded_doc_with_cached_raw_artifact` FAILS (`assertTrue` gets `false`: the 2 GiB root is over the 1 GiB limit and nothing looks at the cache yet). The other two new tests pass already, which is intended: they pin the behaviour that must *not* change.

- [ ] **Step 3: Write the implementation**

In `DocumentVerifier`, add the field, initialise it in the constructor, and short-circuit the gate:

```java
    private final Indexer indexer;
    private final PropertiesProvider propertiesProvider;
    private final SourceExtractor sources;
```

```java
    public DocumentVerifier(Indexer indexer, PropertiesProvider propertiesProvider) {
        this.indexer = indexer;
        this.propertiesProvider = propertiesProvider;
        this.sources = new SourceExtractor(propertiesProvider);
    }
```

```java
    public boolean isRootDocumentSizeAllowed(Document document) {
        if (document.isRootDocument()) {
            return true;
        }
        // A cached raw artifact is read straight off disk: the root is never opened, so its size is
        // irrelevant. Checked before the root lookup, so a cache hit costs one stat and no ES call.
        if (sources.hasCachedEmbeddedSource(document.getProject(), document)) {
            return true;
        }
        long maxSizeBytes = getEmbeddedDocumentDownloadMaxSizeBytes();
        Document rootDocument = getRootDocument(document);
        return rootDocument.getContentLength() < maxSizeBytes;
    }
```

Add the import:

```java
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
```

- [ ] **Step 4: Run the tests to verify they pass**

Run:
```bash
mvn -pl datashare-app -am test -Dtest=DocumentVerifierTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS, all seven tests (four pre-existing plus three new).

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/utils/DocumentVerifier.java \
        datashare-app/src/test/java/org/icij/datashare/utils/DocumentVerifierTest.java
git commit -m "feat(download): allow embedded download when the raw artifact is cached"
```

---

### Task 3: HEAD route sharing GET's decision

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/DocumentResource.java` (`getSourceFile`, lines 92-120; new private method next to it)
- Test: `datashare-app/src/test/java/org/icij/datashare/web/DocumentResourceTest.java` (6 new tests)

**Interfaces:**
- Consumes: `documentVerifier.isRootDocumentSizeAllowed(Document)` (behaviour from Task 2); existing private `getPayload(Document, String, boolean, boolean)`; existing static imports `ok`, `notFoundIfNull`, `isAllowed`, `parseBoolean`; `net.codestory.http.annotations.*` is already wildcard-imported, so `@Head` needs no new import.
- Produces: `HEAD /api/:project/documents/src/:id?routing=:routing` returning `200` / `403` / `404` / `413` with no body. Consumed later by the frontend change (out of scope here).

- [ ] **Step 1: Write the failing tests**

Append to `DocumentResourceTest`:

```java
    @Test
    public void test_head_source_file_root_document_should_respond_200() throws Exception {
        File txtFile = new File(temp.getRoot(), "head.txt");
        MockIndexer.write(txtFile, "text content");
        mockIndexer.indexFile("local-datashare", "id_head_txt", txtFile.toPath(), null, null);

        head("/api/local-datashare/documents/src/id_head_txt").should().respond(200);
    }

    @Test
    public void test_head_source_file_should_have_no_body() throws Exception {
        File txtFile = new File(temp.getRoot(), "head_no_body.txt");
        MockIndexer.write(txtFile, "text content");
        mockIndexer.indexFile("local-datashare", "id_head_no_body", txtFile.toPath(), null, null);

        Response response = head("/api/local-datashare/documents/src/id_head_no_body").response();

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.content()).isEmpty();
        // The same GET does send the bytes, so the empty body above is HEAD's doing, not an empty file.
        get("/api/local-datashare/documents/src/id_head_no_body").should().respond(200).contain("text content");
    }

    @Test
    public void test_head_source_root_too_big_should_respond_413() {
        String path = getClass().getResource("/docs/embedded_doc.eml").getPath();
        Project index = new Project("local-datashare");
        Document documentBar = createDoc("bar").with(index).withContentLength(2L * 1024 * 1024 * 1024).build();
        Document documentFoo = createDoc("foo").with(index).with(path).withParentId("bar").withRootId("bar").build();
        mockIndexer.indexFile("local-datashare", documentBar, documentFoo);

        head("/api/local-datashare/documents/src/foo?routing=bar").should().respond(413);
        // GET and HEAD must agree on the refusal.
        get("/api/local-datashare/documents/src/foo?routing=bar").should().respond(413);
    }

    @Test
    public void test_head_source_root_too_big_with_cached_raw_artifact_should_respond_200() throws Exception {
        String embeddedId = "a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2";
        String path = getClass().getResource("/docs/embedded_doc.eml").getPath();
        Project index = new Project("local-datashare");
        Document documentBar = createDoc("bar").with(index).withContentLength(2L * 1024 * 1024 * 1024).build();
        Document embedded = createDoc(embeddedId).with(index).with(path).withParentId("bar").withRootId("bar").build();
        mockIndexer.indexFile("local-datashare", documentBar, embedded);
        File artifactDir = temp.newFolder("artifacts");
        java.nio.file.Path rawFile = artifactDir.toPath().resolve("local-datashare").resolve("a1").resolve("b2").resolve(embeddedId).resolve("raw");
        Files.createDirectories(rawFile.getParent());
        Files.write(rawFile, "embedded bytes".getBytes());
        when(propertiesProvider.get(ARTIFACT_DIR_OPT)).thenReturn(Optional.of(artifactDir.toString()));

        head("/api/local-datashare/documents/src/" + embeddedId + "?routing=bar").should().respond(200);
    }

    @Test
    public void test_head_source_file_unknown_id_should_respond_404() {
        head("/api/local-datashare/documents/src/unknown_id").should().respond(404);
    }

    @Test
    public void test_head_source_file_forbidden_index_should_respond_403() {
        head("/api/foo_index/documents/src/id").should().respond(403);
    }
```

Add the one missing import (`ARTIFACT_DIR_OPT`, `createDoc`, `Optional`, `when` and `Project` are already imported):

```java
import java.nio.file.Files;
```

Note on coverage: the artifact-hit case is asserted on HEAD only. Asserting `GET` 200 there would drive `EmbeddedDocumentExtractor` over a fixture root and end up testing extract-lib's cache lookup rather than this change; the gate itself is already covered at unit level by Task 2.

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
mvn -pl datashare-app -am test -Dtest=DocumentResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: all six new tests FAIL. The four `head(...)` calls hit no route yet, so they respond `405`/`404` rather than the expected code, and `test_head_source_file_unknown_id_should_respond_404` also documents the pre-existing GET bug (an unknown id NPEs in `document.isRootDocument()` and answers `500`). If a failure shows a different actual code than described, record it and continue: what matters is that it is not the expected one.

- [ ] **Step 3: Write the implementation**

In `DocumentResource`, replace the body of `getSourceFile` with a call to the new shared method and add the HEAD route next to it:

```java
    @Get("/:project/documents/src/:id?routing=:routing&filter_metadata=:filter_metadata")
    public Payload getSourceFile(final String project, final String id,
                                 final String routing, final String filterMetadata, final Context context) throws IOException {
        boolean inline = context.request().query().getBoolean("inline");
        return sourceFile(project, id, routing, context,
                document -> getPayload(document, project, inline, parseBoolean(filterMetadata)));
    }

    @Operation(description = "Tells whether the source of a document can be downloaded, without transferring it. Same decision as GET on the same route (permissions, existence, and the embedded-document size limit), so clients don't have to reimplement the rule.",
                parameters = {
                    @Parameter(name = "project", description = "project id", in = ParameterIn.PATH),
                    @Parameter(name = "id", description = "hash of the document", in = ParameterIn.PATH),
                    @Parameter(name = "routing", description = "routing key if not a root document", in = ParameterIn.QUERY),
                }
    )
    @ApiResponse(responseCode = "200", description = "the source can be downloaded")
    @ApiResponse(responseCode = "403", description = "forbidden if the user doesn't have access to the project or downloads are restricted")
    @ApiResponse(responseCode = "404", description = "if no document is found")
    @ApiResponse(responseCode = "413", description = "if the root document is too large and no raw artifact is cached for this embedded document")
    @Head("/:project/documents/src/:id?routing=:routing")
    public Payload headSourceFile(final String project, final String id, final String routing, final Context context) {
        return sourceFile(project, id, routing, context, document -> ok());
    }

    // Single decision for both verbs of the source route: GET serves the bytes, HEAD serves the
    // verdict alone. Keeping them on one path is what stops the two from drifting apart, which is
    // exactly how the frontend ended up reimplementing the size rule in the first place.
    private Payload sourceFile(final String project, final String id, final String routing,
                               final Context context, final Function<Document, Payload> whenAllowed) {
        boolean isProjectGranted = ((DatashareUser) context.currentUser()).isGranted(project);
        boolean isDownloadAllowed = isAllowed(repository.getProject(project), context.request().clientAddress());
        if (!isProjectGranted || !isDownloadAllowed) {
            return PayloadFormatter.error("You are not allowed to download this document", HttpStatus.FORBIDDEN);
        }
        List<String> sourceExcludes = List.of("content", "content_translated");
        Document document = notFoundIfNull(indexer.get(project, id, routing == null ? id : routing, sourceExcludes));
        if (!documentVerifier.isRootDocumentSizeAllowed(document)) {
            return PayloadFormatter.error("The file or its parent is too large", HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        }
        return whenAllowed.apply(document);
    }
```

Add the import:

```java
import java.util.function.Function;
```

- [ ] **Step 4: Run the tests to verify they pass**

Run:
```bash
mvn -pl datashare-app -am test -Dtest=DocumentResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS, new and pre-existing tests alike. The pre-existing `test_get_source_file*`, `test_get_source_root_too_big`, `test_source_file_not_found_should_return_404` and `test_get_source_file_forbidden_index` all still exercise `getSourceFile`, so they are the regression net for the refactor.

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/web/DocumentResource.java \
        datashare-app/src/test/java/org/icij/datashare/web/DocumentResourceTest.java
git commit -m "feat(api): add HEAD /:project/documents/src/:id"
```

- [ ] **Step 6: Run the full module suites once**

Run:
```bash
mvn -pl datashare-index -am test -Dtest='SourceExtractorTest,ArtifactCoverageCheckerTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl datashare-app -am test -Dtest='Document*Test,BatchDownload*Test' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS. `BatchDownload*Test` matters because `BatchDownloadRunner` shares the gate changed in Task 2 without being edited.

---

## Out of scope

- Frontend: making `useDocumentDownload.isRootTooBig` read this endpoint instead of recomputing the rule (`datashare-client`, same issue, separate branch).
- Any change to artifact production.
- `Content-Length` / `Content-Type` on the HEAD response (rejected in the spec: those values come from the index and can disagree with the bytes served).
