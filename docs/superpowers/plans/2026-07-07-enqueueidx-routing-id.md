# ENQUEUEIDX Routing Id Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Carry the Elasticsearch routing id (root document id) inside ENQUEUEIDX queue entries so downstream tasks can fetch embedded documents.

**Architecture:** A `DocReference` record owns a `<id>|<rootId>` string encoding (bare `<id>` for root documents — structurally backward compatible with old in-flight entries). `EnqueueFromIndexTask` encodes entries; `ExtractNlpTask`, `CategorizeTask` and `ArtifactTask` parse them and fetch via new routing-aware `PipelineTask.getDocument` overloads that dispatch to the existing `Indexer.get(index, id, root[, sourceExcludes])`.

**Tech Stack:** Java 17 (records), Maven, JUnit 4, Mockito, fest-assert, `ElasticsearchRule` (real ES), `MemoryDocumentCollectionFactory`.

**Spec:** `docs/superpowers/specs/2026-07-07-enqueueidx-routing-id-design.md`

## Global Constraints

- Commits: conventional, subject line only, imperative, ≤72 chars, **no body, no Co-Authored-By / AI attribution ever**.
- All new code lives in `datashare-app`, package `org.icij.datashare.tasks`.
- The separator is `|`, split on the **first** occurrence only. Document ids are hex hashes so `|` never appears in an id.
- Poison handling is unchanged: consumers compare the raw polled string to `PipelineTask.STRING_POISON` **before** parsing.
- Do not change search queries, queue types, or the extract-library serialization.

### Running tests in this environment

Single-class test command (from repo root):

```bash
mvn -q -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app \
  -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=<ClassName> test
```

- Add `-o` (offline) if the local `~/.m2` has all artifacts for the current version; if it fails on a missing `datashare` parent POM / `datashare-db` / `datashare-nlp-corenlp` artifact, first run `mvn -q -N -Dgpg.skip=true install` from the repo root, then retry without `-o`.
- `EnqueueFromIndexTaskTest` and `CategorizeTaskIntTest` need Elasticsearch at `http://elasticsearch:9200` (the devenv `devenv_elasticsearch_1` container is mapped via `/etc/hosts`). `DocReferenceTest`, `ExtractNlpTaskTest`, `ArtifactTaskTest` are mock-based and need nothing.

---

### Task 1: `DocReference` value type

**Files:**
- Create: `datashare-app/src/main/java/org/icij/datashare/tasks/DocReference.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/DocReferenceTest.java`

**Interfaces:**
- Consumes: `org.icij.datashare.text.Document` (`getId()`, `getRootDocument()`, `isRootDocument()` — note `getRootDocument()` falls back to `getId()` when the field is null, `Document.java:220`).
- Produces (used by Tasks 2–5):
  - `record DocReference(String id, String rootId)`
  - `static DocReference parse(String entry)` — 1 segment → `(entry, null)`, 2 segments → `(id, root)`, splits on first `|`
  - `static DocReference fromDocument(Document document)` — `(id, null)` for root docs, `(id, rootDocument)` otherwise
  - `String toQueueEntry()` — `id` when `rootId` is null or equals `id`, else `id + "|" + rootId`

- [ ] **Step 1: Write the failing test**

Create `datashare-app/src/test/java/org/icij/datashare/tasks/DocReferenceTest.java`:

```java
package org.icij.datashare.tasks;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.DocumentBuilder.createDoc;

public class DocReferenceTest {
    @Test
    public void test_parse_bare_id_has_no_root() {
        DocReference ref = DocReference.parse("docId");
        assertThat(ref.id()).isEqualTo("docId");
        assertThat(ref.rootId()).isNull();
    }

    @Test
    public void test_parse_entry_with_root() {
        DocReference ref = DocReference.parse("docId|rootId");
        assertThat(ref.id()).isEqualTo("docId");
        assertThat(ref.rootId()).isEqualTo("rootId");
    }

    @Test
    public void test_parse_splits_on_first_separator_only() {
        DocReference ref = DocReference.parse("docId|rootId|garbage");
        assertThat(ref.id()).isEqualTo("docId");
        assertThat(ref.rootId()).isEqualTo("rootId|garbage");
    }

    @Test
    public void test_to_queue_entry_without_root_is_bare_id() {
        assertThat(new DocReference("docId", null).toQueueEntry()).isEqualTo("docId");
    }

    @Test
    public void test_to_queue_entry_with_root_equal_to_id_is_bare_id() {
        assertThat(new DocReference("docId", "docId").toQueueEntry()).isEqualTo("docId");
    }

    @Test
    public void test_to_queue_entry_with_root() {
        assertThat(new DocReference("docId", "rootId").toQueueEntry()).isEqualTo("docId|rootId");
    }

    @Test
    public void test_round_trip() {
        assertThat(DocReference.parse(new DocReference("docId", "rootId").toQueueEntry()))
                .isEqualTo(new DocReference("docId", "rootId"));
        assertThat(DocReference.parse(new DocReference("docId", null).toQueueEntry()))
                .isEqualTo(new DocReference("docId", null));
    }

    @Test
    public void test_from_root_document_has_no_root() {
        DocReference ref = DocReference.fromDocument(createDoc("docId").build());
        assertThat(ref).isEqualTo(new DocReference("docId", null));
    }

    @Test
    public void test_from_embedded_document_has_root() {
        DocReference ref = DocReference.fromDocument(
                createDoc("docId").withRootId("rootId").withParentId("rootId").build());
        assertThat(ref).isEqualTo(new DocReference("docId", "rootId"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
mvn -q -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app \
  -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DocReferenceTest test
```
Expected: COMPILATION ERROR — `DocReference` does not exist.

- [ ] **Step 3: Write the implementation**

Create `datashare-app/src/main/java/org/icij/datashare/tasks/DocReference.java`:

```java
package org.icij.datashare.tasks;

import org.icij.datashare.text.Document;

/**
 * Reference to an indexed document inside a string {@link org.icij.extract.queue.DocumentQueue}:
 * the document id, optionally followed by its Elasticsearch routing id (root document id) for
 * embedded documents, separated by '|'. Bare ids (root documents and legacy queue entries)
 * parse to a null rootId, which keeps old in-flight entries working unchanged.
 */
public record DocReference(String id, String rootId) {
    public static final String SEPARATOR = "|";

    public static DocReference parse(String entry) {
        int separatorIndex = entry.indexOf(SEPARATOR);
        return separatorIndex < 0 ? new DocReference(entry, null) :
                new DocReference(entry.substring(0, separatorIndex), entry.substring(separatorIndex + 1));
    }

    public static DocReference fromDocument(Document document) {
        return document.isRootDocument() ? new DocReference(document.getId(), null) :
                new DocReference(document.getId(), document.getRootDocument());
    }

    public String toQueueEntry() {
        return rootId == null || rootId.equals(id) ? id : id + SEPARATOR + rootId;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Same command as Step 2. Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tasks/DocReference.java \
        datashare-app/src/test/java/org/icij/datashare/tasks/DocReferenceTest.java
git commit -m "feat: add DocReference to encode doc id with routing id in queues"
```

---

### Task 2: `EnqueueFromIndexTask` enqueues encoded entries

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/EnqueueFromIndexTask.java:89`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/EnqueueFromIndexTaskTest.java`

**Interfaces:**
- Consumes: `DocReference.fromDocument(Document)` / `toQueueEntry()` (Task 1).
- Produces: queue entries in `<id>` / `<id>|<rootId>` format, consumed by Tasks 3–5.

Requires Elasticsearch (see Global Constraints). `ElasticsearchIndexer.add(index, doc)` routes by `doc.getRootDocument()`, so indexing a doc built with `.withRootId(...)` creates a genuine embedded document.

- [ ] **Step 1: Write the failing test**

Add to `EnqueueFromIndexTaskTest.java` (imports needed: `org.icij.datashare.text.Document` — the rest is already imported):

```java
@Test
public void test_embedded_document_is_enqueued_with_its_root_id() throws Exception {
    Document root = createDoc("root_doc").with(project(es.getIndexName())).build();
    Document embedded = createDoc("embedded_doc").with(project(es.getIndexName()))
            .withParentId(root.getId()).withRootId(root.getId()).build();
    indexer.add(es.getIndexName(), root);
    indexer.add(es.getIndexName(), embedded);
    Map<String, Object> properties = Map.of(
            "defaultProject", es.getIndexName(),
            "stages", "ENQUEUEIDX",
            "queueName", "test:queue",
            NLP_PIPELINE_OPT, Pipeline.Type.OPENNLP.name());
    MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();

    new EnqueueFromIndexTask(factory, indexer,
            new Task<>(EnqueueFromIndexTask.class.getName(), new User("test"), properties), null).call();

    assertThat(factory.queues.get("test:queue:nlp"))
            .contains(root.getId(), embedded.getId() + "|" + root.getId());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
mvn -q -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app \
  -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=EnqueueFromIndexTaskTest test
```
Expected: FAIL — the queue contains `embedded_doc`, not `embedded_doc|root_doc`.

- [ ] **Step 3: Implement the encoding**

In `EnqueueFromIndexTask.java`, replace line 89:

```java
                docsToProcess.forEach(doc -> outputQueue.add(doc.getId()));
```

with:

```java
                docsToProcess.forEach(doc -> outputQueue.add(DocReference.fromDocument((Document) doc).toQueueEntry()));
```

(`Document` is already imported; `DocReference` is in the same package.)

- [ ] **Step 4: Run tests to verify they pass**

Same command as Step 2. Expected: PASS — all `EnqueueFromIndexTaskTest` tests, including the pre-existing ones (root docs still enqueue as bare ids).

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tasks/EnqueueFromIndexTask.java \
        datashare-app/src/test/java/org/icij/datashare/tasks/EnqueueFromIndexTaskTest.java
git commit -m "fix: enqueue routing id with doc id in ENQUEUEIDX queue entries"
```

---

### Task 3: routing-aware `PipelineTask.getDocument` + `ExtractNlpTask`

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/PipelineTask.java` (after the two existing `getDocument` methods, lines 71–77)
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/ExtractNlpTask.java:99-101`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/ExtractNlpTaskTest.java`

**Interfaces:**
- Consumes: `DocReference.parse(String)` (Task 1); existing `Indexer.get(index, id, root)` / `get(index, id, root, sourceExcludes)`.
- Produces (used by Tasks 4–5):
  - `protected Document getDocument(Indexer indexer, String projectName, DocReference ref)`
  - `protected Document getDocument(Indexer indexer, String projectName, DocReference ref, List<String> sourceExcludes)`
  - `ExtractNlpTask.findNamedEntities(Project, String queueEntry)` now accepts encoded entries (bare ids keep working).

- [ ] **Step 1: Write the failing test**

Add to `ExtractNlpTaskTest.java`:

```java
@Test
public void test_on_message_with_routing_fetches_doc_with_root_id() throws Exception {
    when(pipeline.initialize(any())).thenReturn(true);
    Document doc = createDoc("embedded").withRootId("rootId").withParentId("rootId").build();
    when(pipeline.process(doc)).thenReturn(emptyList());
    when(indexer.get("projectName", doc.getId(), "rootId")).thenReturn(doc);

    nlpTask.findNamedEntities(project("projectName"), doc.getId() + "|rootId");

    verify(pipeline).initialize(ENGLISH);
    verify(pipeline).process(doc);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
mvn -q -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app \
  -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ExtractNlpTaskTest test
```
Expected: FAIL — `pipeline.process` never called, because `indexer.get("projectName", "embedded|rootId")` returns null.

- [ ] **Step 3: Add the `PipelineTask` overloads**

In `PipelineTask.java`, insert after the existing `getDocument(Indexer, String, String, List<String>)` method (line 77):

```java
    protected Document getDocument(Indexer indexer, String projectName, DocReference ref) {
        return ref.rootId() == null ? getDocument(indexer, projectName, ref.id()) :
                warnIfNull(indexer.get(projectName, ref.id(), ref.rootId()), projectName, ref.id());
    }

    protected Document getDocument(Indexer indexer, String projectName, DocReference ref, List<String> sourceExcludes) {
        return ref.rootId() == null ? getDocument(indexer, projectName, ref.id(), sourceExcludes) :
                warnIfNull(indexer.get(projectName, ref.id(), ref.rootId(), sourceExcludes), projectName, ref.id());
    }
```

- [ ] **Step 4: Make `ExtractNlpTask` parse queue entries**

In `ExtractNlpTask.java`, `findNamedEntities` currently starts (lines 99–101):

```java
    void findNamedEntities(final Project project, final String id) throws InterruptedException {
        try {
            Document doc = getDocument(indexer, project.getName(), id);
```

Change to:

```java
    void findNamedEntities(final Project project, final String queueEntry) throws InterruptedException {
        try {
            Document doc = getDocument(indexer, project.getName(), DocReference.parse(queueEntry));
```

No other change: the poll loop still compares the raw string to `STRING_POISON` before calling `findNamedEntities`.

- [ ] **Step 5: Run tests to verify they pass**

Same command as Step 2. Expected: PASS — all `ExtractNlpTaskTest` tests (bare-id tests unchanged: `parse` returns a null root and dispatches to the old `get(project, id)`).

- [ ] **Step 6: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tasks/PipelineTask.java \
        datashare-app/src/main/java/org/icij/datashare/tasks/ExtractNlpTask.java \
        datashare-app/src/test/java/org/icij/datashare/tasks/ExtractNlpTaskTest.java
git commit -m "fix(nlp): fetch embedded documents with routing id from queue entries"
```

---

### Task 4: `CategorizeTask` parses and forwards encoded entries

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/CategorizeTask.java:57-92` (the `call()` method)
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/CategorizeTaskIntTest.java`

**Interfaces:**
- Consumes: `DocReference.parse(String)` (Task 1); `getDocument(Indexer, String, DocReference)` (Task 3); encoded entries from Task 2.
- Produces: forwards the **original encoded entry** to its output queue so routing survives the categorize→nlp chain.

Requires Elasticsearch (see Global Constraints).

- [ ] **Step 1: Write the failing test**

Add to `CategorizeTaskIntTest.java`:

```java
@Test
public void test_integration_with_enqueue_idx_for_embedded_docs() throws Exception {
    // GIVEN a root document and an embedded document routed under it
    Document root = aDocWithContentType("message/rfc822");
    Document embedded = DocumentBuilder.createDoc(UUID.randomUUID().toString())
            .ofContentType("application/pdf")
            .withParentId(root.getId())
            .withRootId(root.getId())
            .build();
    index(List.of(root, embedded));

    Map<String, Object> args = Map.of(
            DEFAULT_PROJECT_OPT, es.getIndexName(),
            "stages", "ENQUEUEIDX,CATEGORIZE");

    // WHEN
    new EnqueueFromIndexTask(documentCollectionFactory, indexer,
            new Task<>(EnqueueFromIndexTask.class.getName(), User.local(), args), null).call();

    assertThat(documentCollectionFactory.queues.get("extract:queue:categorize"))
            .contains(embedded.getId() + "|" + root.getId());

    documentCollectionFactory.createQueue("extract:queue:categorize", String.class).add(STRING_POISON);

    new CategorizeTask(indexer, documentCollectionFactory,
            new Task<>(CategorizeTask.class.getName(), "categorizeTask3", User.local(), args), NO_OP_PROGRESS).call();

    // THEN the embedded doc got categorized (fetched and updated with routing)...
    Document updated = indexer.get(es.getIndexName(), embedded.getId(), root.getId());
    assertThat(updated.getContentTypeCategory()).isEqualTo(ContentTypeCategory.DOCUMENT);
    // ...and the encoded entry is forwarded downstream
    assertThat(documentCollectionFactory.queues.get("extract:queue:nlp"))
            .contains(embedded.getId() + "|" + root.getId());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
mvn -q -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app \
  -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CategorizeTaskIntTest test
```
Expected: FAIL — `updated.getContentTypeCategory()` is null: `CategorizeTask` called `indexer.get(project, "id|root")`, found nothing, and skipped the doc.

- [ ] **Step 3: Implement parsing and forwarding**

In `CategorizeTask.java`, `call()` currently reads (lines 60–78, abridged):

```java
        String docId;
        ...
        while (!(STRING_POISON.equals(docId = inputQueue.poll( (pollingIntervalSeconds * 1000L), TimeUnit.MILLISECONDS)))
                && nbMaxPolls > 0) {
            try {
                if (docId != null) {
                    Document retrievedFromIndexer = getDocument(indexer, project.getName(), docId);
                    if(retrievedFromIndexer != null) {
                        enrichWithType(retrievedFromIndexer);
                    }
                    nbMessages++;
                    processed.incrementAndGet();
                    progressCallback.apply(getProgressRate());
                    if(!outputQueue.offer(docId)){
                        logger.warn("unable to offer {} to queue {}", docId, outputQueue.getName());
                    }
```

Rename `docId` to `queueEntry` throughout `call()` and parse it for the fetch, forwarding the original entry:

```java
        String queueEntry;
        ...
        while (!(STRING_POISON.equals(queueEntry = inputQueue.poll( (pollingIntervalSeconds * 1000L), TimeUnit.MILLISECONDS)))
                && nbMaxPolls > 0) {
            try {
                if (queueEntry != null) {
                    Document retrievedFromIndexer = getDocument(indexer, project.getName(), DocReference.parse(queueEntry));
                    if(retrievedFromIndexer != null) {
                        enrichWithType(retrievedFromIndexer);
                    }
                    nbMessages++;
                    processed.incrementAndGet();
                    progressCallback.apply(getProgressRate());
                    if(!outputQueue.offer(queueEntry)){
                        logger.warn("unable to offer {} to queue {}", queueEntry, outputQueue.getName());
                    }
```

The rest of the loop (else branch, catch, poison offer at the end) is unchanged apart from the variable rename.

- [ ] **Step 4: Run tests to verify they pass**

Same command as Step 2. Expected: PASS — all `CategorizeTaskIntTest` tests, including pre-existing bare-id ones.

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tasks/CategorizeTask.java \
        datashare-app/src/test/java/org/icij/datashare/tasks/CategorizeTaskIntTest.java
git commit -m "fix(categorize): fetch embedded docs with routing id and forward it downstream"
```

---

### Task 5: `ArtifactTask` parses encoded entries

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/ArtifactTask.java:55-60`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactTaskTest.java`

**Interfaces:**
- Consumes: `DocReference.parse(String)` (Task 1); `getDocument(Indexer, String, DocReference, List<String>)` (Task 3).
- Produces: nothing consumed by later tasks (terminal stage).

Mock-based — `MockIndexer.indexFile(index, id, path, contentType, routing)` already stubs `get(index, id, routing, sourceExcludes)` for embedded docs (`MockIndexer.java:56`).

- [ ] **Step 1: Write the failing test**

Add to `ArtifactTaskTest.java` (new imports: `java.util.List`, `static org.mockito.Mockito.verify`):

```java
@Test(timeout = 10000)
public void test_entry_with_routing_fetches_document_with_root_id() throws Exception {
    Path path = Path.of(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).toURI());
    mockIndexer.indexFile("prj", EMBEDDED_DOC_SHA256, path, "message/rfc822", "rootId");
    DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
    queue.add(EMBEDDED_DOC_SHA256 + "|rootId");

    Long numberOfDocuments = runArtifactTask();

    verify(mockEs).get("prj", EMBEDDED_DOC_SHA256, "rootId", List.of("content", "content_translated"));
    assertThat(numberOfDocuments).isEqualTo(1);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
mvn -q -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app \
  -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ArtifactTaskTest test
```
Expected: FAIL — Mockito verify: `get` was called with `("prj", "<sha256>|rootId", sourceExcludes)`, never with the routing signature; `numberOfDocuments` is 0.

- [ ] **Step 3: Implement parsing**

In `ArtifactTask.java`, `call()` currently reads (lines 55–60):

```java
        String docId;
        long nbDocs = 0;
        long nbSkipped = 0;
        while ((docId = inputQueue.poll(pollingInterval, TimeUnit.SECONDS)) != null) {
            try {
                Document doc = getDocument(indexer, project.name, docId, sourceExcludes);
```

Change to:

```java
        String queueEntry;
        long nbDocs = 0;
        long nbSkipped = 0;
        while ((queueEntry = inputQueue.poll(pollingInterval, TimeUnit.SECONDS)) != null) {
            try {
                Document doc = getDocument(indexer, project.name, DocReference.parse(queueEntry), sourceExcludes);
```

- [ ] **Step 4: Run tests to verify they pass**

Same command as Step 2. Expected: PASS — all `ArtifactTaskTest` tests, including the bare-id and unknown-id warning tests.

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tasks/ArtifactTask.java \
        datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactTaskTest.java
git commit -m "fix(artifact): fetch embedded docs with routing id from queue entries"
```

---

### Task 6: full regression pass

**Files:** none (verification only).

- [ ] **Step 1: Run every touched test class**

```bash
mvn -q -pl commons-test,datashare-cli,datashare-api,datashare-index,datashare-tasks,datashare-app \
  -Dgpg.skip=true -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='DocReferenceTest,EnqueueFromIndexTaskTest,ExtractNlpTaskTest,ExtractNlpTaskIntTest,CategorizeTaskIntTest,ArtifactTaskTest' test
```

Expected: PASS. `ExtractNlpTaskIntTest` is included because it exercises the ENQUEUEIDX→NLP chain end to end and must keep passing with encoded entries.

- [ ] **Step 2: Verify working tree is clean**

Run: `git status --short` — expected: empty (all changes committed in Tasks 1–5).
