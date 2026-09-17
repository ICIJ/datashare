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
