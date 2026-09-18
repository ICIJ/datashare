package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.nlp.LinguaLanguageGuesser;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

/**
 * The stage rewrites a document's language only when re-detection disagrees with the indexed one,
 * detecting on exactly what index time detects on (content, then file name), and reports a run it
 * could not complete instead of returning a count for it.
 */
public class LanguageDetectTaskTest {
    private static final String FRENCH_CONTENT =
            "Le petit chat noir dort paisiblement sur le canape pres de la fenetre ensoleillee.";
    private static final String GIBBERISH_CONTENT = "1234567890 42 -- !!! ??? ... 2026/09/17 +33 06 07";
    private static final List<String> EXCLUDES = List.of("content_translated");
    private static final String QUEUE_NAME = "extract:queue:language";
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
        enqueue("docId");

        assertThat(runTask()).isEqualTo(1);

        verify(mockEs).update("prj", "docId", Map.of("language", "FRENCH"), "docId");
    }

    @Test(timeout = 30000)
    public void test_skips_the_update_when_language_is_already_correct() throws Exception {
        when(mockEs.get("prj", "docId", "docId", EXCLUDES)).thenReturn(frenchDocTagged(Language.FRENCH, "docId"));
        enqueue("docId");

        assertThat(runTask()).isEqualTo(1);

        verify(mockEs, never()).update(anyString(), anyString(), anyMap(), anyString());
    }

    @Test(timeout = 30000)
    public void test_unknown_overwrites_a_wrong_english_language() throws Exception {
        Document doc = DocumentBuilder.createDoc("docId").with(new Project("prj"))
                .with(GIBBERISH_CONTENT).with(Language.ENGLISH).build();
        when(mockEs.get("prj", "docId", "docId", EXCLUDES)).thenReturn(doc);
        enqueue("docId");

        assertThat(runTask()).isEqualTo(1);

        verify(mockEs).update("prj", "docId", Map.of("language", "UNKNOWN"), "docId");
    }

    @Test(timeout = 30000)
    public void test_falls_back_to_the_file_name_when_the_document_has_no_content() throws Exception {
        Document scan = DocumentBuilder.createDoc("scanId").with(new Project("prj")).with("")
                .with(Paths.get("/corpus/Contrat_de_travail_et_conditions_generales.pdf")).with(Language.ENGLISH)
                .build();
        when(mockEs.get("prj", "scanId", "scanId", EXCLUDES)).thenReturn(scan);
        enqueue("scanId");

        assertThat(runTask()).isEqualTo(1);

        // index time guesses on the file name when a not-yet-OCRed scan carries no text, so re-detection
        // must not downgrade that document to UNKNOWN
        verify(mockEs).update("prj", "scanId", Map.of("language", "FRENCH"), "scanId");
    }

    @Test(timeout = 30000)
    public void test_updates_an_embedded_document_with_its_root_routing() throws Exception {
        Document embedded = DocumentBuilder.createDoc("childId").with(new Project("prj"))
                .with(FRENCH_CONTENT).with(Language.ENGLISH)
                .withParentId("rootId").withRootId("rootId").build();
        when(mockEs.get("prj", "childId", "rootId", EXCLUDES)).thenReturn(embedded);
        enqueue("childId|rootId");

        assertThat(runTask()).isEqualTo(1);

        verify(mockEs).update("prj", "childId", Map.of("language", "FRENCH"), "rootId");
    }

    @Test(timeout = 30000)
    public void test_skips_a_document_missing_from_the_index() throws Exception {
        when(mockEs.get("prj", "goneId", "goneId", EXCLUDES)).thenReturn(null);
        enqueue("goneId");

        assertThat(runTask()).isEqualTo(0);

        verify(mockEs, never()).update(anyString(), anyString(), anyMap(), anyString());
    }

    @Test(timeout = 30000)
    public void test_several_workers_drain_the_same_queue() throws Exception {
        when(mockEs.get("prj", "firstId", "firstId", EXCLUDES)).thenReturn(frenchDocTagged(Language.ENGLISH, "firstId"));
        when(mockEs.get("prj", "otherId", "otherId", EXCLUDES)).thenReturn(frenchDocTagged(Language.ENGLISH, "otherId"));
        enqueue("firstId");
        enqueue("otherId");

        assertThat(runTask(factory, Map.of("defaultProject", "prj", "parallelism", "2"))).isEqualTo(2);

        verify(mockEs).update("prj", "firstId", Map.of("language", "FRENCH"), "firstId");
        verify(mockEs).update("prj", "otherId", Map.of("language", "FRENCH"), "otherId");
    }

    @Test(timeout = 30000)
    public void test_a_dead_worker_fails_the_run_instead_of_returning_a_count() {
        try {
            runTask(brokenQueueFactory(), Map.of("defaultProject", "prj"));
            fail("a worker dying on a broken queue client must fail the run, not report it as done");
        } catch (Exception e) {
            // without this the run returns 0 and looks like an empty queue, stranding everything still in it
            assertThat(e).isInstanceOf(WorkersFailed.class);
            assertThat(e.getMessage()).contains("1 of 1 LANGUAGE worker(s)");
        }
    }

    private Document frenchDocTagged(Language language, String id) {
        return DocumentBuilder.createDoc(id).with(new Project("prj")).with(FRENCH_CONTENT).with(language).build();
    }

    private void enqueue(String queueEntry) {
        factory.createQueue(QUEUE_NAME, String.class).add(queueEntry);
    }

    @SuppressWarnings("unchecked")
    private DocumentCollectionFactory<String> brokenQueueFactory() {
        DocumentQueue<String> brokenQueue = mock(DocumentQueue.class);
        when(brokenQueue.poll()).thenThrow(new RuntimeException("queue client is gone"));
        DocumentCollectionFactory<String> brokenFactory = mock(DocumentCollectionFactory.class);
        when(brokenFactory.createQueue(anyString(), eq(String.class))).thenReturn(brokenQueue);
        return brokenFactory;
    }

    private Long runTask() throws Exception {
        return runTask(factory, Map.of("defaultProject", "prj"));
    }

    private Long runTask(DocumentCollectionFactory<String> queueFactory, Map<String, Object> args) throws Exception {
        return new LanguageDetectTask(queueFactory, mockEs, guesser, new UpstreamGate.Factory(taskRepository),
                new Task<>(LanguageDetectTask.class.getName(), User.local(), args), null).call();
    }
}
