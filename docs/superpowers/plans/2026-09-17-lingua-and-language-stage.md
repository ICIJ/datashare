# Lingua Guesser + LANGUAGE Stage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Optimaize with lingua for language detection, then add a LANGUAGE pipeline stage that re-detects and updates the `language` field of already-indexed documents.

**Architecture:** Two stacked branches. Branch 1 (`feat/lingua-language-guesser`, off `main`) swaps the detector behind the existing `LanguageGuesser` interface; it ships alone. Branch 2 (`feat/language-stage`, off branch 1) adds `Stage.LANGUAGE` + `LanguageDetectTask` (ArtifactTask's drain-queue/worker-pool shape) and a routing-aware partial-update overload on `Indexer`.

**Tech Stack:** Java 17, Maven multi-module, Guice (assisted inject), lingua 1.2.2, Elasticsearch (es-java client), JUnit 4 + fest-assert + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-17-language-stage-design.md`

## Global Constraints

- Test env: ES at `elasticsearch:9200`, Redis at `redis:6379` (both already running in the devenv).
- Single-test runs need `-Dsurefire.failIfNoSpecifiedTests=false`; multiple classes in `-Dtest` are COMMA-separated (`+` silently runs nothing = fake green); never drop `-am`.
- Run Maven in the background and poll; never block on it. Kill stale builds first.
- A handful of test classes fail on any branch for environment reasons: before claiming a regression, check the same failure exists on the base branch.
- Commit with the `/commit` skill. No Claude credit in commits. Never commit `docs/superpowers/**`.
- Branch discipline: branch 1 off `main`, branch 2 off branch 1. Integration is fast-forward/rebase, no squash.
- Comments: default zero; only non-obvious *why*.
- ES stores `language` as the datashare `Language` enum constant name (`"FRENCH"`), pinned by `ElasticsearchSpewerTest.java:232`. Nothing in this plan may change that format.

---

## Branch 1: `feat/lingua-language-guesser`

### Task 1: LinguaLanguageGuesser

**Files:**
- Modify: `datashare-app/pom.xml` (~line 100, replace the optimaize dependency block)
- Create: `datashare-app/src/main/java/org/icij/datashare/nlp/LinguaLanguageGuesser.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/nlp/LinguaLanguageGuesserTest.java`

**Interfaces:**
- Consumes: `org.icij.datashare.text.indexing.LanguageGuesser` (datashare-api, one method: `Language guess(String text)`), `org.icij.datashare.text.Language.parse(String)` (returns `UNKNOWN` for unmappable codes, `Language.java:66-83`).
- Produces: `LinguaLanguageGuesser implements LanguageGuesser`, no-arg constructor (no checked exception), `static final int MAX_DETECTION_LENGTH = 10_000`. Task 2 binds it; branch 2's task injects it via the interface.

- [ ] **Step 1: Create the branch**

```bash
git checkout main && git checkout -b feat/lingua-language-guesser
```

- [ ] **Step 2: Swap the dependency in datashare-app/pom.xml**

Replace (datashare-app/pom.xml:100-104):

```xml
        <dependency>
            <groupId>com.optimaize.languagedetector</groupId>
            <artifactId>language-detector</artifactId>
            <version>0.6</version>
        </dependency>
```

with:

```xml
        <dependency>
            <groupId>com.github.pemistahl</groupId>
            <artifactId>lingua</artifactId>
            <version>1.2.2</version>
        </dependency>
```

(lingua pulls kotlin-stdlib transitively; no other pom change. The optimaize class still compiles until Task 2 deletes it — deleting the optimaize dep now is fine because Task 2 removes its last usage in this same branch; if the module fails to compile between tasks, that is expected and resolved by Task 2. To keep every commit green instead, keep BOTH dependencies in this task and delete the optimaize block in Task 2 — prefer this.)

So: ADD the lingua block next to the optimaize block, do not delete optimaize yet.

- [ ] **Step 3: Write the failing test**

`datashare-app/src/test/java/org/icij/datashare/nlp/LinguaLanguageGuesserTest.java`:

```java
package org.icij.datashare.nlp;

import org.icij.datashare.text.Language;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class LinguaLanguageGuesserTest {
    private static final LinguaLanguageGuesser guesser = new LinguaLanguageGuesser();

    @Test(timeout = 30000)
    public void test_detects_english_prose() {
        assertThat(guesser.guess("The quick brown fox jumps over the lazy dog near the river bank every morning."))
                .isEqualTo(Language.ENGLISH);
    }

    @Test(timeout = 30000)
    public void test_detects_french_prose() {
        assertThat(guesser.guess("Le petit chat noir dort paisiblement sur le canape pres de la fenetre ensoleillee."))
                .isEqualTo(Language.FRENCH);
    }

    @Test(timeout = 30000)
    public void test_detects_short_text() {
        assertThat(guesser.guess("Bonjour, comment allez-vous ?")).isEqualTo(Language.FRENCH);
    }

    @Test(timeout = 30000)
    public void test_empty_and_blank_text_is_unknown() {
        assertThat(guesser.guess("")).isEqualTo(Language.UNKNOWN);
        assertThat(guesser.guess("   \n\t ")).isEqualTo(Language.UNKNOWN);
        assertThat(guesser.guess(null)).isEqualTo(Language.UNKNOWN);
    }

    @Test(timeout = 30000)
    public void test_letterless_gibberish_is_unknown_not_english() {
        // the optimaize guesser silently returned ENGLISH here: the bug behind the wrongly-english corpus
        assertThat(guesser.guess("1234567890 42 -- !!! ??? ... 2026/09/17 +33 06 07")).isEqualTo(Language.UNKNOWN);
    }

    @Test(timeout = 30000)
    public void test_samples_beyond_the_head_of_large_documents() {
        // head full of URL boilerplate, real text past the midpoint: a head-only sample misdetects this
        String sample = "https://example.org/a/b/c?d=e&f=g http://foo.bar/baz#qux ";
        StringBuilder sb = new StringBuilder();
        while (sb.length() < LinguaLanguageGuesser.MAX_DETECTION_LENGTH * 2) {
            sb.append(sample);
        }
        String french = "Le petit chat noir dort paisiblement sur le canape pres de la fenetre ensoleillee. ";
        for (int i = 0; i < 200; i++) {
            sb.append(french);
        }
        assertThat(guesser.guess(sb.toString())).isEqualTo(Language.FRENCH);
    }

    @Test(timeout = 60000)
    public void test_every_lingua_language_maps_to_a_datashare_language() {
        for (com.github.pemistahl.lingua.api.Language linguaLanguage :
                com.github.pemistahl.lingua.api.Language.values()) {
            if (linguaLanguage == com.github.pemistahl.lingua.api.Language.UNKNOWN) {
                continue;
            }
            assertThat(Language.parse(linguaLanguage.getIsoCode639_1().toString()))
                    .as(linguaLanguage.name())
                    .isNotEqualTo(Language.UNKNOWN);
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

```bash
mvn -pl datashare-app -am test -Dtest=LinguaLanguageGuesserTest -Dsurefire.failIfNoSpecifiedTests=false
```

Run in the background, poll the output. Expected: COMPILATION ERROR, `LinguaLanguageGuesser` does not exist. (First run also downloads the ~100MB lingua jar.)

- [ ] **Step 5: Write the implementation**

`datashare-app/src/main/java/org/icij/datashare/nlp/LinguaLanguageGuesser.java`:

```java
package org.icij.datashare.nlp;

import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import com.google.inject.Singleton;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.LanguageGuesser;

@Singleton
public class LinguaLanguageGuesser implements LanguageGuesser {
    // Bounds per-document detection CPU on multi-MB content; 10k chars is plenty for n-gram detection.
    static final int MAX_DETECTION_LENGTH = 10_000;
    private final LanguageDetector languageDetector = LanguageDetectorBuilder.fromAllSpokenLanguages().build();
    // ponytail: all 75 spoken languages, lazily loaded, high accuracy; models can grow toward ~1GB
    // on a polyglot corpus — switch to withLowAccuracyMode() or a language subset if memory hurts.

    @Override
    public Language guess(String text) {
        if (text == null || text.isBlank()) {
            return Language.UNKNOWN;
        }
        com.github.pemistahl.lingua.api.Language detected = languageDetector.detectLanguageOf(sample(text));
        return detected == com.github.pemistahl.lingua.api.Language.UNKNOWN ? Language.UNKNOWN :
               Language.parse(detected.getIsoCode639_1().toString());
    }

    // Three slices spread across the document: a head-only sample misdetects documents whose first
    // pages are URLs, headers or other boilerplate.
    private static String sample(String text) {
        if (text.length() <= MAX_DETECTION_LENGTH) {
            return text;
        }
        int slice = MAX_DETECTION_LENGTH / 3;
        int mid = (int) (text.length() * 0.45);
        int end = (int) (text.length() * 0.90);
        return text.substring(0, slice)
                + text.substring(mid, mid + slice)
                + text.substring(end, Math.min(end + slice, text.length()));
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Same command as Step 4, in the background. Expected: 7 tests, 0 failures. If `test_letterless_gibberish_is_unknown_not_english` fails because lingua guesses a language from stray letters, make the gibberish strictly letterless (digits, punctuation, whitespace only) — the point of the test is the no-silent-English contract, not one specific string.

- [ ] **Step 7: Commit**

Use the `/commit` skill. Scope: `datashare-app/pom.xml`, the new class, the new test. Message shape: `feat(nlp): add lingua-based language guesser`.

### Task 2: Bind lingua, delete Optimaize

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/mode/CommonMode.java:38` (import) and `:487-491` (provider)
- Modify: `datashare-app/pom.xml` (delete the optimaize dependency block kept in Task 1)
- Delete: `datashare-app/src/main/java/org/icij/datashare/nlp/OptimaizeLanguageGuesser.java`
- Delete: `datashare-app/src/test/java/org/icij/datashare/nlp/OptimaizeLanguageGuesserTest.java`

**Interfaces:**
- Consumes: `LinguaLanguageGuesser` from Task 1 (no-arg ctor, no checked exception).
- Produces: the Guice `LanguageGuesser` binding now returns `LinguaLanguageGuesser`; everything downstream (`ElasticsearchSpewer`, `NerResource`, branch 2's task) picks it up through the interface.

- [ ] **Step 1: Rebind in CommonMode**

At CommonMode.java:487-491 replace:

```java
    @Provides
    @Singleton
    LanguageGuesser provideLanguageGuesser() throws IOException {
        return new OptimaizeLanguageGuesser();
    }
```

with:

```java
    @Provides
    @Singleton
    LanguageGuesser provideLanguageGuesser() {
        return new LinguaLanguageGuesser();
    }
```

Swap the import at CommonMode.java:38 from `org.icij.datashare.nlp.OptimaizeLanguageGuesser` to `org.icij.datashare.nlp.LinguaLanguageGuesser`. If removing `throws IOException` orphans the `java.io.IOException` import in CommonMode, leave the import if still used elsewhere in the file (it is — check before removing).

- [ ] **Step 2: Delete Optimaize**

```bash
git rm datashare-app/src/main/java/org/icij/datashare/nlp/OptimaizeLanguageGuesser.java \
       datashare-app/src/test/java/org/icij/datashare/nlp/OptimaizeLanguageGuesserTest.java
```

Delete the `com.optimaize.languagedetector` dependency block from `datashare-app/pom.xml`.

- [ ] **Step 3: Verify nothing else references Optimaize**

```bash
grep -rn "optimaize\|Optimaize" --include="*.java" --include="pom.xml" datashare-app/src datashare-api/src datashare-index/src datashare-cli/src pom.xml
```

Expected: no hits. (`datashare-index` spewer tests mock `LanguageGuesser`, they never touch the impl.)

- [ ] **Step 4: Run the affected tests**

```bash
mvn -pl datashare-app -am test -Dtest=LinguaLanguageGuesserTest,NerResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Background + poll. Expected: PASS (NerResourceTest exercises the guesser through the web layer's binding path if it uses the real one; if it mocks the guesser it still proves compilation).

- [ ] **Step 5: Commit**

`/commit` skill. Message shape: `feat(nlp): replace optimaize with lingua for language detection`. Body notes the behavior change: unsure/blank documents now get UNKNOWN instead of a silent ENGLISH default.

---

## Branch 2: `feat/language-stage` (stacked on branch 1)

### Task 3: Routing-aware partial update on Indexer

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/text/indexing/Indexer.java:70` (add overload next to the existing one)
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchIndexer.java:188-193`
- Test: `datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchIndexerTest.java`

**Interfaces:**
- Consumes: existing `void update(String indexName, String id, Map<String, Object> fields)` (Indexer.java:70) and the ES join-field routing rule (children live on their root's shard).
- Produces: `void update(String indexName, String id, Map<String, Object> fields, String routing) throws IOException` on the `Indexer` interface. Task 4's task calls exactly this signature.

- [ ] **Step 1: Create the stacked branch**

```bash
git checkout feat/lingua-language-guesser && git checkout -b feat/language-stage
```

- [ ] **Step 2: Write the failing test**

Add to `ElasticsearchIndexerTest.java` (uses the class's existing `es` rule, `indexer` field and `createDoc` helper; add an import for `org.icij.datashare.text.Language` if missing):

```java
    @Test
    public void test_update_fields_with_routing_updates_an_embedded_document() throws IOException {
        Document root = createDoc("routingUpdateRoot").build();
        indexer.add(es.getIndexName(), root);
        indexer.add(es.getIndexName(), createDoc("routingUpdateChild")
                .withParentId(root.getId()).withRootId(root.getId())
                .with(Language.ENGLISH).build());

        indexer.update(es.getIndexName(), "routingUpdateChild",
                Map.of("language", Language.FRENCH.name()), root.getId());

        Document updated = indexer.get(es.getIndexName(), "routingUpdateChild", root.getId());
        assertThat(updated.getLanguage()).isEqualTo(Language.FRENCH);
        assertThat(updated.getRootDocument()).isEqualTo(root.getId());
    }
```

The first test also pins the stored format end-to-end: a real `Document` read back through `indexer.get` must see `Language.FRENCH`, proving `Language.name()` is the value the index expects.

- [ ] **Step 3: Run to verify failure**

```bash
mvn -pl datashare-index -am test -Dtest=ElasticsearchIndexerTest -Dsurefire.failIfNoSpecifiedTests=false
```

Background + poll. Expected: COMPILATION ERROR on the 4-arg `update` (does not exist yet).

- [ ] **Step 4: Implement the overload**

In `Indexer.java`, next to the existing 3-arg declaration (Indexer.java:67-70), add:

```java
    // Same partial update, routed explicitly: embedded documents live on their root's shard
    // (join field), so updating them by id requires the root id as routing.
    void update(String indexName, String id, Map<String, Object> fields, String routing) throws IOException;
```

In `ElasticsearchIndexer.java`, replace the 3-arg implementation (lines 188-193) with:

```java
    @Override
    public void update(String indexName, String id, Map<String, Object> fields) throws IOException {
        update(indexName, id, fields, null);
    }

    @Override
    public void update(String indexName, String id, Map<String, Object> fields, String routing) throws IOException {
        UpdateRequest.Builder<Map<String, Object>, Object> req =
                new UpdateRequest.Builder<Map<String, Object>, Object>().index(indexName).id(id)
                                                                        .refresh(esCfg.refreshPolicy).doc(fields);
        ofNullable(routing).ifPresent(req::routing);
        client.update(req.build(), Object.class);
    }
```

(`ofNullable` is already statically imported in this class; verify, else use `java.util.Optional.ofNullable`.) If other `Indexer` implementations exist (grep `implements Indexer`), add the 4-arg method there too — a mock-only implementation delegates to the 3-arg one.

- [ ] **Step 5: Run to verify pass**

Same command as Step 3. Expected: the new test PASSES, no regression in the class.

- [ ] **Step 6: Commit**

`/commit` skill. Message shape: `feat(index): add routing-aware partial update to Indexer`.

### Task 4: Stage.LANGUAGE + LanguageDetectTask

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/Stage.java:8-9`
- Create: `datashare-app/src/main/java/org/icij/datashare/tasks/LanguageDetectTask.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/LanguageDetectTaskTest.java`

**Interfaces:**
- Consumes: `PipelineTask<String>` (gate ctor, `drained()`, `isLegacySentinel`, `causedByInterrupt`, `getDocument(indexer, project, ref, excludes)` — PipelineTask.java:44,84-124), `DocReference.parse/routing()`, `LanguageGuesser.guess`, Task 3's `indexer.update(index, id, fields, routing)`, `UpstreamGate.Factory#forTask`.
- Produces: `LanguageDetectTask` with ctor `(DocumentCollectionFactory<String>, Indexer, LanguageGuesser, UpstreamGate.Factory, @Assisted Task<Long>, @Assisted Function<Double, Void>)`; `call()` returns the number of successfully processed documents (updated + unchanged). Task 5 wires this exact ctor into the factory and CliApp.

- [ ] **Step 1: Add the enum value**

In `Stage.java:8-9`, change the values list to end with `ARTIFACT(false), LANGUAGE(false);`:

```java
    SCAN(true), SCANIDX(false), DEDUPLICATE(false), INDEX(true), ENQUEUEIDX(false), CATEGORIZE(false), NLP(true),
    CREATENLPBATCHESFROMIDX(false), BATCHNLP(false), ARTIFACT(false), LANGUAGE(false);
```

Appended last on purpose: existing ordinals do not move.

- [ ] **Step 2: Write the failing test**

`datashare-app/src/test/java/org/icij/datashare/tasks/LanguageDetectTaskTest.java`:

```java
package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.nlp.LinguaLanguageGuesser;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class LanguageDetectTaskTest {
    private static final String FRENCH_CONTENT =
            "Le petit chat noir dort paisiblement sur le canape pres de la fenetre ensoleillee.";
    private static final List<String> EXCLUDES = List.of("content_translated");
    @Mock Indexer mockEs;
    private final MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();
    private final TaskRepositoryMemory taskRepository = new TaskRepositoryMemory();
    private final LanguageGuesser guesser = new LinguaLanguageGuesser();

    @Before
    public void setUp() {
        initMocks(this);
    }

    @Test(timeout = 30000)
    public void test_updates_a_wrongly_english_document() throws Exception {
        when(mockEs.get("prj", "docId", "docId", EXCLUDES)).thenReturn(frenchDocTagged(Language.ENGLISH, "docId"));
        factory.createQueue("extract:queue:language", String.class).add("docId");

        assertThat(runTask()).isEqualTo(1);

        verify(mockEs).update("prj", "docId", Map.of("language", "FRENCH"), "docId");
    }

    @Test(timeout = 30000)
    public void test_skips_the_update_when_language_is_already_correct() throws Exception {
        when(mockEs.get("prj", "docId", "docId", EXCLUDES)).thenReturn(frenchDocTagged(Language.FRENCH, "docId"));
        factory.createQueue("extract:queue:language", String.class).add("docId");

        assertThat(runTask()).isEqualTo(1);

        verify(mockEs, never()).update(anyString(), anyString(), anyMap(), anyString());
    }

    @Test(timeout = 30000)
    public void test_updates_an_embedded_document_with_its_root_routing() throws Exception {
        Document embedded = DocumentBuilder.createDoc("childId").with(new Project("prj"))
                .with(FRENCH_CONTENT).with(Language.ENGLISH)
                .withParentId("rootId").withRootId("rootId").build();
        when(mockEs.get("prj", "childId", "rootId", EXCLUDES)).thenReturn(embedded);
        factory.createQueue("extract:queue:language", String.class).add("childId|rootId");

        assertThat(runTask()).isEqualTo(1);

        verify(mockEs).update("prj", "childId", Map.of("language", "FRENCH"), "rootId");
    }

    @Test(timeout = 30000)
    public void test_skips_a_document_missing_from_the_index() throws Exception {
        when(mockEs.get("prj", "goneId", "goneId", EXCLUDES)).thenReturn(null);
        factory.createQueue("extract:queue:language", String.class).add("goneId");

        assertThat(runTask()).isEqualTo(0);

        verify(mockEs, never()).update(anyString(), anyString(), anyMap(), anyString());
    }

    private Document frenchDocTagged(Language language, String id) {
        return DocumentBuilder.createDoc(id).with(new Project("prj")).with(FRENCH_CONTENT).with(language).build();
    }

    private Long runTask() throws Exception {
        return new LanguageDetectTask(factory, mockEs, guesser, new UpstreamGate.Factory(taskRepository),
                new Task<>(LanguageDetectTask.class.getName(), User.local(), Map.of("defaultProject", "prj")),
                null).call();
    }
}
```

If `DocumentBuilder` lacks one of `.with(Project)`, `.with(String)`, `.with(Language)`, `.withParentId`, `.withRootId`, check `MockIndexer.java:35-43` and `ElasticsearchSpewer.java:283-297` for the actual builder calls and adapt — do not change `DocumentBuilder`.

- [ ] **Step 3: Run to verify failure**

```bash
mvn -pl datashare-app -am test -Dtest=LanguageDetectTaskTest -Dsurefire.failIfNoSpecifiedTests=false
```

Background + poll. Expected: COMPILATION ERROR, `LanguageDetectTask` does not exist.

- [ ] **Step 4: Write the task**

`datashare-app/src/main/java/org/icij/datashare/tasks/LanguageDetectTask.java`:

```java
package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.DocReference;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.PARALLELISM_OPT;

@TemporalSingleActivityWorkflow(name = "language", activityOptions = @ActivityOpts(timeout = "P1D"))
@TaskGroup(TaskGroupType.Java)
public class LanguageDetectTask extends PipelineTask<String> {
    private static final List<String> SOURCE_EXCLUDES = List.of("content_translated");
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final LanguageGuesser languageGuesser;
    private final Project project;
    private final int parallelism;
    private final ExecutorService executor;

    @Inject
    public LanguageDetectTask(DocumentCollectionFactory<String> factory, Indexer indexer,
                              LanguageGuesser languageGuesser, final UpstreamGate.Factory gateFactory,
                              @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> updateCallback) {
        super(Stage.LANGUAGE, taskView.getUser(), factory, new PropertiesProvider(taskView.args), String.class,
              gateFactory.forTask(taskView));
        this.indexer = indexer;
        this.languageGuesser = languageGuesser;
        project = Project.project((String) taskView.args.getOrDefault(DEFAULT_PROJECT_OPT, DEFAULT_DEFAULT_PROJECT));
        parallelism = Math.max(1, propertiesProvider.get(PARALLELISM_OPT).map(Integer::parseInt).orElse(1));
        executor = Executors.newFixedThreadPool(parallelism);
    }

    @Override
    public void cancel(boolean requeue) {
        super.cancel(requeue);
        executor.shutdownNow();
    }

    @Override
    public Long call() throws Exception {
        super.call();
        logger.info("re-detecting document languages for project {} from queue {} with {} worker(s)",
                    project.name, inputQueue.getName(), parallelism);
        AtomicLong nbUpdated = new AtomicLong(0);
        AtomicLong nbUnchanged = new AtomicLong(0);
        AtomicLong nbSkipped = new AtomicLong(0);
        AtomicLong nbFailed = new AtomicLong(0);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < parallelism; i++) {
                futures.add(executor.submit(() -> runWorker(nbUpdated, nbUnchanged, nbSkipped, nbFailed)));
            }
            for (Future<?> future : futures) {
                future.get();
            }
            if (Thread.interrupted()) {
                throw new InterruptedException("cancelled while draining " + inputQueue.getName());
            }
        } finally {
            executor.shutdownNow();
        }
        logger.info("language re-detection done: {} updated, {} unchanged, {} skipped, {} failed",
                    nbUpdated.get(), nbUnchanged.get(), nbSkipped.get(), nbFailed.get());
        if (nbSkipped.get() + nbFailed.get() > 0) {
            logger.error("{} document(s) were skipped or failed, re-run the LANGUAGE stage for them",
                         nbSkipped.get() + nbFailed.get());
        }
        return nbUpdated.get() + nbUnchanged.get();
    }

    private void runWorker(AtomicLong nbUpdated, AtomicLong nbUnchanged, AtomicLong nbSkipped, AtomicLong nbFailed) {
        while (!Thread.currentThread().isInterrupted()) {
            String queueEntry;
            try {
                queueEntry = inputQueue.poll();
            } catch (RuntimeException e) {
                if (causedByInterrupt(e)) {
                    Thread.currentThread().interrupt();
                    break;
                }
                throw e;
            }
            if (queueEntry == null) {
                if (drained()) {
                    break;
                }
                try {
                    Thread.sleep(UPSTREAM_POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            if (isLegacySentinel(queueEntry)) {
                continue;
            }
            DocReference ref = DocReference.parse(queueEntry);
            try {
                Document doc = getDocument(indexer, project.name, ref, SOURCE_EXCLUDES);
                if (doc == null) {
                    nbSkipped.incrementAndGet();
                    continue;
                }
                Language guess = languageGuesser.guess(doc.getContent());
                if (guess == doc.getLanguage()) {
                    nbUnchanged.incrementAndGet();
                } else {
                    indexer.update(project.name, doc.getId(), Map.of("language", guess.name()), ref.routing());
                    nbUpdated.incrementAndGet();
                }
            } catch (Exception e) {
                if (causedByInterrupt(e)) {
                    Thread.currentThread().interrupt();
                    break;
                }
                logger.error("language detection failed for document <{}>", ref.id(), e);
                nbFailed.incrementAndGet();
            }
        }
    }
}
```

`Map.of("language", guess.name())` is the format Task 3's round-trip test pinned against a real index.

- [ ] **Step 5: Run to verify pass**

Same command as Step 3. Expected: 4 tests PASS.

- [ ] **Step 6: Commit**

`/commit` skill. Message shape: `feat(tasks): add LANGUAGE stage to re-detect indexed document languages`.

### Task 5: Wiring + full verification

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/DatashareTaskFactory.java:32` (add a factory method next to `createArtifactTask`)
- Modify: `datashare-app/src/main/java/org/icij/datashare/CliApp.java:192-196` (stage-to-task map) plus the import block (~line 20)

**Interfaces:**
- Consumes: `LanguageDetectTask` ctor from Task 4, `Stage.LANGUAGE` from Task 4.
- Produces: `LanguageDetectTask createLanguageDetectTask(Task<Long> taskView, Function<Double, Void> updateCallback);` on `DatashareTaskFactory`; `Stage.LANGUAGE -> LanguageDetectTask.class` in CliApp's map. The `@TemporalSingleActivityWorkflow` annotation from Task 4 is compile-time processed (`TemporalWorkflowGenerator.java:48`), no extra Temporal registration.

- [ ] **Step 1: Add the factory method**

In `DatashareTaskFactory.java`, next to line 32:

```java
    LanguageDetectTask createLanguageDetectTask(Task<Long> taskView, Function<Double, Void> updateCallback);
```

- [ ] **Step 2: Add the CliApp map entry**

At CliApp.java:192-196 the map currently holds 9 entries; add the 10th (`Map.of` maxes out at exactly 10 pairs, so this fits — if a later stage needs an 11th, switch to `Map.ofEntries`):

```java
            Map.of(Stage.SCAN, ScanTask.class, Stage.SCANIDX, ScanIndexTask.class, Stage.DEDUPLICATE,
                   DeduplicateTask.class, Stage.INDEX, IndexTask.class, Stage.ENQUEUEIDX, EnqueueFromIndexTask.class,
                   Stage.CATEGORIZE, CategorizeTask.class, Stage.CREATENLPBATCHESFROMIDX,
                   CreateNlpBatchesFromIndex.class, Stage.NLP, ExtractNlpTask.class, Stage.ARTIFACT,
                   ArtifactTask.class, Stage.LANGUAGE, LanguageDetectTask.class);
```

Add `import org.icij.datashare.tasks.LanguageDetectTask;` beside the ArtifactTask import (CliApp.java:20).

- [ ] **Step 3: Run the branch's test surface**

```bash
mvn -pl datashare-app -am test \
  -Dtest=LanguageDetectTaskTest,LinguaLanguageGuesserTest,ArtifactTaskTest,EnqueueFromIndexTaskTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Background + poll (this compiles datashare-api, datashare-index, datashare-app: the whole change surface). Expected: PASS. If `EnqueueFromIndexTaskTest` does not exist under that name, grep for the actual test class of `EnqueueFromIndexTask` and use it.

- [ ] **Step 4: Commit**

`/commit` skill. Message shape: `feat(cli): wire the LANGUAGE stage into the pipeline`.

- [ ] **Step 5: Hostile self-review of both branches' diffs**

```bash
git diff main...feat/language-stage
```

Per hunk, list every behavior change (intended and not). Known intended ones to confirm and no more: INDEX-time guesses can now be non-English/UNKNOWN; `NerResource` language endpoint follows; 3-arg `indexer.update` behavior unchanged (delegates with null routing); `Stage` gains a value (check nothing iterates `Stage.values()` expecting ARTIFACT last: `grep -rn "Stage.values()" --include="*.java" datashare-*/src/main`).

- [ ] **Step 6: Report, then stop**

Report the two branches, their commits, and the manual run command for the 500k-doc backfill:

```bash
datashare --mode CLI --stages ENQUEUEIDX,LANGUAGE --defaultProject <project> \
  --searchQuery '{"term":{"language":"ENGLISH"}}' --parallelism 4
```

PR creation is a separate step the developer triggers (two PRs, the second based on the first, ff/rebase integration).

---

## Self-review (done at plan time)

- Spec coverage: guesser swap (Task 1-2), UNKNOWN semantics (Task 1), three-slice sampling (Task 1), Stage.LANGUAGE (Task 4), enqueue reuse (nothing to build — verified `EnqueueFromIndexTask` already carries routing and `--searchQuery`), routing overload (Task 3), task + counters + skip-unchanged + UNKNOWN-overwrites (Task 4), wiring incl. Temporal via annotation processor (Task 5), no new CLI options (whole plan), stored format pinned (Task 3 round-trip + `guess.name()`).
- Known risk, called out: lingua's Kotlin API surface from Java (`getIsoCode639_1().toString()`, static builder methods) is per its README's Java examples; if a name differs at compile time, fix the call site — the design doesn't move.
- Deliberate deviation from spec: `withMinimumRelativeDistance` not set (spec left it test-driven; the gibberish test passes without it — add only if a real-corpus sample shows near-tie noise).
