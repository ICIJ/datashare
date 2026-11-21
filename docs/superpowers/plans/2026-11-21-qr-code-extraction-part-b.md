# QR code extraction, Part B: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `QRCODES` pipeline stage that decodes 2D codes in image documents already in a project's index (standalone images and images attached to other files) and appends `QR code: <value>` lines plus `metadata.tika_metadata_qr_codes`, without a reindex.

**Architecture:** extract-lib gets one method, `EmbeddedDocumentExtractor.forEachEmbed`, that walks a root once without an artifact cache and hands every embed's id and spooled bytes to a callback. In datashare, `QRCodeTask` (a `PipelineTask` on a new `Payload.NONE` stage, like `SCANIDX`) scrolls the project's `image/*` documents sorted by `rootDocument`, decodes standalone files and cached embeds directly, and batches uncached embeds per root so each root is parsed once. Each document with values gets one partial update.

**Tech Stack:** Java 17, Tika 3.3.0, ZXing core (already a transitive dependency through extract-lib), Elasticsearch 8 Java client, JUnit 4 + fest-assert, Mockito, Maven.

**Spec:** `docs/superpowers/specs/2026-11-17-qr-code-extraction-design.md`, section "Part B". Part A plan: `docs/superpowers/plans/2026-11-17-qr-code-extraction-part-a.md` (shipped as extract 9.25.0, datashare commit `12cac01b9`).

## Global Constraints

- extract-lib work: new worktree `~/Repositories/extract/.worktrees/embed-walk`, branch `feat/embed-walk`, from `origin/master`. Do not touch the main extract checkout or other worktrees.
- datashare work: this worktree, branch `feat/qr-code-extraction` (PR #2554).
- Stage name `QRCODES`, not a main stage, `Payload.NONE`. Appended last in `Stage` so no existing ordinal moves.
- Selection: `contentType` prefix `image/`, applied by the stage itself. No `--searchQuery` dependency.
- Skip any document whose `_source` already has `metadata.tika_metadata_qr_codes`. This per-document check guarantees no duplicate lines.
- Content line: exactly `QR code: <value>`, one per value, appended after the existing text.
- Update: `content`, `contentTextLength` (= new `content.length()`), `metadata.tika_metadata_qr_codes`, in one partial update routed by `rootDocument`. Never touch `contentLength`.
- A document with no code is left untouched.
- Never one root parse per image: uncached embeds of the same root share one `forEachEmbed` walk.
- A root that fails to re-parse, a missing file, or an id the walk never yields is logged and counted; the stage moves on.
- The stage does not need `--qrCodes`.
- Decoding goes through `org.icij.extract.qrcode.QRCodeDecoder.decode(Path)` (extract 9.25.0), which never throws.
- Metadata key: `new FieldNames().forMetadata(QRCodeParserDecorator.QR_CODES.getName())` (= `tika_metadata_qr_codes`), no string literal.
- Test fixtures are generated in the test (ZXing `QRCodeWriter`, `ImageIO`, hand-written MIME). Never real corpus files.
- Commits through the `icij:commit` skill. `make format` then `make lint` before any push.
- datashare single-test runs: `mvn -pl datashare-app -am test -Dtest=A,B -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.gitcommitid.skip=true`, in the background. ES at `elasticsearch:9200`.

## Review Focus

1. **Images of one root split across scroll pages** (mailbox with more images than `scrollSize`): still one walk per root. Test in Task 4 (`scrollSize` 1).
2. **Root that fails to re-parse** (file gone or corrupt): its images count as failed, the next root still runs. Test in Task 4.
3. **Embed id the walk never yields** (digester drift between index time and now): counted failed and logged, not silently counted as "no code". Test in Task 4.
4. **Image indexed with empty content** (OCR off): the first line has no leading newline. Test in Task 3.
5. **Standalone image whose file moved**: counted failed, the stage finishes and other documents get updated. Test in Task 3.

---

## Part B1: extract-lib

### Task 1: `EmbeddedDocumentExtractor.forEachEmbed`

**Files:**
- Modify: `extract-lib/src/main/java/org/icij/extract/extractor/EmbeddedDocumentExtractor.java` (next to `extractAll`, plus a `DigestVisitingEmbeddedDocumentExtractor` beside `DigestAllEmbeddedDocumentExtractor`)
- Test: `extract-lib/src/test/java/org/icij/extract/extractor/EmbeddedDocumentExtractorForEachEmbedTest.java`

**Interfaces:**
- Produces: `public void forEachEmbed(TikaDocument root, BiConsumer<String, Path> visitor) throws SAXException, TikaException, IOException`. Calls `visitor.accept(embedId, spooledBytes)` once per embed at every depth, ids identical to index time. `spooledBytes` is only readable during the call. Works with or without an artifact path and writes nothing to it.

- [ ] **Step 1: Write the failing test**

```java
@Test public void test_visits_every_embed_once_with_index_time_ids_and_readable_bytes()
    // zip holding a.png and b.png (any bytes), written to a TemporaryFolder
    // index-time ids: Extractor with DocumentFactory().withIdentifier(new DigestIdentifier("SHA-256", UTF_8)),
    //   setDigester(new UpdatableDigester("prj", "SHA-256")); extract, read and close the reader;
    //   expected = root.getEmbeds() ids
    // new EmbeddedDocumentExtractor(new UpdatableDigester("prj", "SHA-256")).forEachEmbed(freshRoot, (id, bytes) -> {
    //   visited.add(id); sizes.add(Files.size(bytes)); })
    // assertThat(visited).containsOnly(expected...) and hasSize(2); every size > 0
```

- [ ] **Step 2: Run it to see it fail**

Run: `mvn -q -pl extract-lib test -Dtest=EmbeddedDocumentExtractorForEachEmbedTest`
Expected: compilation FAIL, `cannot find symbol: method forEachEmbed`.

- [ ] **Step 3: Implement** `forEachEmbed` as `extractAll` without the artifact-path guard, using a `DigestVisitingEmbeddedDocumentExtractor` whose `documentCallback` calls `visitor.accept(digest, spooled)` and returns `false`.

- [ ] **Step 4: Run it plus the existing on-demand tests**

Run: `mvn -q -pl extract-lib test -Dtest='EmbeddedDocument*Test'`
Expected: all pass.

- [ ] **Step 5: Commit** (`icij:commit`, message `feat(extractor): walk every embed of a root without an artifact cache`)

### Checkpoint: extract release (Pierre)

Push `feat/embed-walk`, open the extract PR, merge, then `make release NEW_VERSION=9.26.0` on master (or the next version). Task 2 waits for that artifact.

---

## Part B2: datashare

### Task 2: bump extract, `SourceExtractor` helpers

**Files:**
- Modify: `pom.xml:100` (`extract.version`)
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/SourceExtractor.java:201-219` (`cachedEmbeddedSourceLength`), and add `forEachEmbed` next to `extractEmbeddedSources`
- Test: `datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/SourceExtractorTest.java`

**Interfaces:**
- Consumes: `EmbeddedDocumentExtractor.forEachEmbed` (Task 1).
- Produces:
  - `public Path cachedEmbeddedSourcePath(Project project, Document document)`: the raw payload path when readable, sidecar readable and size > 0, else `null`. `cachedEmbeddedSourceLength` is rewritten on top of it (same results).
  - `public void forEachEmbed(Project project, Document embedded, BiConsumer<String, Path> visitor) throws TikaException, IOException, SAXException`: walks `embedded.getPath()` (the root file) once. Digester, identifier and OCR flag chosen exactly as `extractEmbeddedSources` does, tika version from the document metadata as `getEmbeddedSource` does.

- [ ] **Step 1: Write the failing tests**

```java
@Test public void test_cached_embedded_source_path_is_the_raw_payload()
    // artifactDir with ArtifactPath.dir(projectRoot, id)/raw (non-empty) + raw.json
    // -> equals that raw path
@Test public void test_cached_embedded_source_path_is_null_for_an_empty_payload()   // zero-byte raw -> null
@Test public void test_cached_embedded_source_path_is_null_without_artifact_dir()   // -> null
@Test public void test_for_each_embed_yields_the_indexed_embed_id()
    // reuse the file's existing embedded fixture (the one getEmbeddedSource tests use):
    // visited ids contain that fixture document's id
```

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -pl datashare-index -am test -Dtest=SourceExtractorTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.gitcommitid.skip=true` (background)
Expected: compilation FAIL on `cachedEmbeddedSourcePath` and `forEachEmbed`.

- [ ] **Step 3: Bump** `extract.version` to the Checkpoint version and **implement** both methods.

- [ ] **Step 4: Run them again**

Same command. Expected: all `SourceExtractorTest` tests pass.

- [ ] **Step 5: Commit** (`icij:commit`, message `feat(index): walk a root's embeds once from the source extractor`)

### Task 3: `QRCODES` stage over standalone images

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/Stage.java:12-16` (append `QRCODES(false, Payload.NONE)` after `ENTITIES`)
- Modify: `datashare-api/src/main/java/org/icij/datashare/tasks/TaskType.java` (`QR_CODES("org.icij.datashare.tasks.QRCodeTask")`)
- Modify: `datashare-app/src/main/java/org/icij/datashare/CliApp.java:319-326` (`TASK_CLASSES` entry)
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/DatashareTaskFactory.java` (`QRCodeTask createQRCodeTask(Task<Long> taskView, Function<Double, Void> updateCallback);`, name required by `TaskFactoryHelper`'s `create%s` lookup)
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/command/StageRunCommand.java` (help line `"  QRCODES        Add QR code values to images already indexed (runs alone)"` and example `datashare -P my-project stage run --stages QRCODES`)
- Create: `datashare-app/src/main/java/org/icij/datashare/tasks/QRCodeTask.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/QRCodeTaskIntTest.java`, `datashare-tasks/src/test/java/org/icij/datashare/asynctasks/TaskTypeTest.java`, `datashare-app/src/test/java/org/icij/datashare/CliAppTest.java:306` (already fails once `QRCODES` exists without a task class)

**Interfaces:**
- Consumes: `SourceExtractor.cachedEmbeddedSourcePath` (Task 2), `QRCodeDecoder.decode(Path)`.
- Produces:
  - `QRCodeTask extends PipelineTask<String>`, annotated `@TemporalSingleActivityWorkflow(name = "qr-codes", activityOptions = @ActivityOpts(timeout = "P7D"))` and `@TaskGroup(TaskGroupType.Java)`.
  - `@Inject QRCodeTask(DocumentCollectionFactory<String> factory, Indexer indexer, @Assisted Task<Long> taskView, @Assisted Function<Double, Void> updateCallback)` and a package-private constructor adding a `SourceExtractor` (Task 4 spies on it).
  - `static String appendLines(String content, List<String> values)`.
  - `call()` returns updated + no-code documents.
  - Private test helpers in `QRCodeTaskIntTest`: `png(Path file, String value)` and `eml(Path file, Path... attachments)`.

- [ ] **Step 1: Write the failing tests**

```java
// TaskTypeTest
@Test public void test_fromName_qr_codes_fqdn_returns_type()
    // TaskType.fromName("org.icij.datashare.tasks.QRCodeTask") -> Optional.of(TaskType.QR_CODES)

// QRCodeTaskTest-style unit cases, inside QRCodeTaskIntTest
@Test public void test_append_lines_to_empty_content()
    // appendLines("", List.of("a", "b")) -> "QR code: a\nQR code: b\n"
@Test public void test_append_lines_after_text()
    // appendLines("hello", List.of("a")) -> "hello\nQR code: a\n"
    // appendLines("hello\n", List.of("a")) -> "hello\nQR code: a\n"

// QRCodeTaskIntTest (@ClassRule ElasticsearchRule, TemporaryFolder corpus)
// setup per test: png(corpus/qr.png, "https://example.org/qr"), plus a plain PNG with no code;
// run IndexTask (ocr false, qrCodes absent) over them exactly as ArtifactCoverageIntTest:56-60 does
@Test public void test_appends_the_value_to_a_standalone_image()
    // run QRCodeTask -> qr.png document: content ends with "QR code: https://example.org/qr\n",
    //   contentTextLength == content.length(), metadata tika_metadata_qr_codes == ["https://example.org/qr"],
    //   contentLength unchanged; the no-code PNG document has no qr_codes key and the same content as before
@Test public void test_a_second_run_changes_nothing()
    // run twice -> content has exactly one "QR code:" line; second call() returns 1 (only the no-code PNG re-decoded)
@Test public void test_skips_a_document_already_decoded_at_index_time()
    // index with qrCodes=true instead -> QRCodeTask leaves content identical (one "QR code:" line)
@Test public void test_a_missing_file_does_not_stop_the_stage()
    // delete a second QR PNG after indexing -> the first still updated, call() completes
```

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -pl datashare-app -am test -Dtest=QRCodeTaskIntTest,CliAppTest,TaskTypeTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.gitcommitid.skip=true` (background, poll)
Expected: compilation FAIL, `cannot find symbol: class QRCodeTask` / `QR_CODES`. Show the output before Step 3.

- [ ] **Step 3: Implement** the wiring above and `QRCodeTask` for root images:
  - At start: fail with `IllegalArgumentException` when `!indexer.exists(project)`, then `indexer.createIndex(project)`, which on an existing index only adds the `qr_codes` mapping (`ElasticsearchIndexer.java:639-643`).
  - Scroll: `indexer.search(List.of(project), Document.class).withPrefixQuery("contentType", "image/")`, `withoutSource("content_translated")`, `limit(scrollSize)`, `sort("rootDocument", ASC)`, scroll options as `EnqueueFromIndexTask:59-62`. `updateCallback.apply(seen / totalHits)` after each page.
  - Per document: skip when the metadata key is present; root documents (`isRootDocument()`) decode `getPath()`, counted failed when `!Files.isReadable(path)` (the decoder returns empty for a missing file, which would otherwise read as "no code"); non-root documents are left for Task 4 (count nothing yet).
  - Values found: `indexer.update(project, id, Map.of("content", c, "contentTextLength", c.length(), "metadata", Map.of(QR_KEY, values)), rootDocument)`.
  - Counters as a record (updated, noCode, alreadyDecoded, failed), summary log like `LanguageDetectTask.logSummary`, an error log asking to re-run `QRCODES` when failed > 0.

- [ ] **Step 4: Run them again**

Same command. Expected: all pass, `CliAppTest:306` included.

- [ ] **Step 5: Commit** (`icij:commit`, message `feat(tasks): add QRCODES stage for indexed images`)

### Task 4: embedded images, cached and uncached

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/QRCodeTask.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/QRCodeTaskIntTest.java`

**Interfaces:**
- Consumes: `SourceExtractor.cachedEmbeddedSourcePath`, `SourceExtractor.forEachEmbed` (Task 2), the `eml` test helper (Task 3).

- [ ] **Step 1: Write the failing tests**

```java
// corpus: mail.eml with two QR PNG attachments ("one", "two"), indexed with ocr false, qrCodes absent;
// QRCodeTask built with spy(new SourceExtractor(props))
@Test public void test_parses_an_uncached_root_once_for_all_its_images()
    // both attachment documents get their line and metadata
    // verify(sourceExtractor, times(1)).forEachEmbed(any(), any(), any())
@Test public void test_groups_a_root_split_across_scroll_pages()
    // scrollSize "1" -> same assertions, still times(1)
@Test public void test_reads_cached_embeds_without_parsing_the_root()
    // index with artifactDir set (INDEX writes raw) -> both updated, verify(sourceExtractor, never()).forEachEmbed(...)
@Test public void test_a_root_that_fails_to_parse_does_not_stop_the_stage()
    // two emails; truncate the first file to 0 bytes after indexing -> the second email's images updated
@Test public void test_an_embed_the_walk_never_yields_counts_as_failed()
    // doReturn/doAnswer the spy's forEachEmbed to visit nothing -> no update, no exception, call() returns 0
```

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -pl datashare-app -am test -Dtest=QRCodeTaskIntTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.gitcommitid.skip=true` (background)
Expected: the five new tests FAIL on missing `QR code:` lines in attachment content. Show the output before Step 3.

- [ ] **Step 3: Implement** in `QRCodeTask`:
  - Non-root document with `cachedEmbeddedSourcePath != null`: decode that path.
  - Otherwise append it to a pending group. The scroll is sorted by `rootDocument`, so a document with a different root flushes the group first; the last group flushes after the scroll ends. The group lives across pages.
  - Flush: map the group by id, call `sourceExtractor.forEachEmbed(project, group.get(0), (id, bytes) -> ...)`, decode and update inside the callback for ids in the map (bytes are gone after it returns), remove them from the map. Ids left in the map count as failed with one error log naming the root. An exception from the walk counts the whole group as failed and logs the root once.

- [ ] **Step 4: Run them again**

Same command. Expected: all `QRCodeTaskIntTest` tests pass.

- [ ] **Step 5: Commit** (`icij:commit`, message `feat(tasks): catch up QR codes in embedded images once per root`)

- [ ] **Step 6: Changed-module suites in the background**

Run: `mvn -pl datashare-api,datashare-index,datashare-app,datashare-cli,datashare-tasks -am test -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.gitcommitid.skip=true` in the background, poll and report each check. Expected: no new failures. Compare any failure against `main` before blaming this branch (known env failures: see memory `reference_datashare_local_test_gotchas`).

---

## Suggested follow-ups (not in this plan)

- Run root walks on `PipelineTask`'s worker pool (`--parallelism`) if a large mailbox project is too slow single-threaded.
- Stop a `forEachEmbed` walk once every wanted id is found (`shouldParseEmbedded`), for roots with few images among many embeds.
- PDF inline images in OCR-off projects still need a reindex with `--qrCodes` (spec decision, 2026-11-18).
