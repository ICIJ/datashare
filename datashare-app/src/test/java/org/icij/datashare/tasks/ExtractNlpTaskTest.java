package org.icij.datashare.tasks;

import static java.util.Collections.emptyList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.tasks.PipelineTask.STRING_POISON;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.Project.project;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Stream;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

public class ExtractNlpTaskTest {
    @Mock
    private Indexer indexer;
    @Mock
    private AbstractPipeline pipeline;
    private final MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();
    private ExtractNlpTask nlpTask;
    private final String INPUT_QUEUE_NAME = "extract:queue:nlp";

    @Before
    public void setUp() {
        initMocks(this);
        nlpTask =
            new ExtractNlpTask(indexer, pipeline, factory, new Task<>(ExtractNlpTask.class.getName(), User.local(),
                Map.of("maxContentLength", "8", "batchSize", "2")),
                null
            );
        factory.queues.values().forEach(q -> q.drainTo(new ArrayList<>()));
    }

    @Test
    public void test_find_doc_named_entities_does_nothing_when_doc_not_found_in_index() throws Exception {
        nlpTask.findDocNamedEntities(project("projectName"), "unknownId");

        verify(pipeline, never()).initialize(any(Language.class));
        verify(pipeline, never()).processDoc(any());
        verify(pipeline, never()).processDoc(any(), anyInt(), anyInt());
    }

    @Test
    public void test_extract_from_texts_does_nothing_when_doc_not_found_in_index() throws Exception {
        DocumentQueue<String> inputQueue = factory.getQueues(INPUT_QUEUE_NAME, String.class).get(0);
        inputQueue.add("unknownId");
        inputQueue.add(STRING_POISON);
        nlpTask.extractFromTexts();

        verify(pipeline, never()).initialize(any(Language.class));
        verify(pipeline, never()).processText(any(), any());
    }

    @Test
    public void test_find_doc_named_entities_does_nothing_when_init_fails() throws Exception {
        when(pipeline.initialize(any())).thenReturn(false);
        when(indexer.get(anyString(), anyString(), anyString())).thenReturn(createDoc("content").build());

        nlpTask.findDocNamedEntities(project("projectName"), "id");
        verify(pipeline, never()).processDoc(any());
        verify(pipeline, never()).processDoc(any(), anyInt(), anyInt());
    }

    @Test
    public void test_extract_from_texts_does_nothing_when_init_fails() throws Exception {
        when(pipeline.initialize(any())).thenReturn(false);
        DocumentQueue<String> inputQueue = factory.getQueues(INPUT_QUEUE_NAME, String.class).get(0);
        inputQueue.add("docId");
        inputQueue.add(STRING_POISON);

        nlpTask.extractFromTexts();

        verify(pipeline, never()).processText(any(), any());
    }

    @Test
    public void test_find_doc_named_entities_chunks_doc_when_too_large() throws InterruptedException {
        when(pipeline.initialize(any())).thenReturn(true);
        Document doc = createDoc("huge_doc").with("0123456789abcdef0123456789abcdef+").build();
        when(pipeline.processDoc(doc)).thenReturn(emptyList());
        when(indexer.get(anyString(), anyString())).thenReturn(doc);

        nlpTask.findDocNamedEntities(project("projectName"), doc.getId());

        verify(pipeline).initialize(ENGLISH);
        verify(pipeline).processDoc(doc, 8, 0);
        verify(pipeline).processDoc(doc, 8, 8);
        verify(pipeline).processDoc(doc, 8, 16);
        verify(pipeline).processDoc(doc, 8, 24);
    }

    @Test
    public void test_should_process_docs_by_batch_grouped_by_language() throws InterruptedException {
        // Given
        when(pipeline.initialize(any())).thenReturn(true);
        Document enDoc0 = createDoc("enId0").with("content").with(ENGLISH).build();
        Document enDoc1 = createDoc("enId1").with("long content").with(ENGLISH).build();
        Document frDoc0 = createDoc("frId0").with("contenu long").with(FRENCH).build();
        Document frDoc1 = createDoc("frId1").with("contenu").with(FRENCH).build();
        when(indexer.get(anyString(), same(enDoc0.getId()))).thenReturn(enDoc0);
        when(indexer.get(anyString(), same(enDoc1.getId()))).thenReturn(enDoc1);
        when(indexer.get(anyString(), same(frDoc0.getId()))).thenReturn(frDoc0);
        when(indexer.get(anyString(), same(frDoc1.getId()))).thenReturn(frDoc1);
        DocumentQueue<String> inputQueue = factory.getQueues(INPUT_QUEUE_NAME, String.class).get(0);
        inputQueue.add(enDoc0.getId());
        inputQueue.add(enDoc1.getId());
        inputQueue.add(frDoc0.getId());
        inputQueue.add(frDoc1.getId());
        inputQueue.add(STRING_POISON);

        // When
        nlpTask.extractFromTexts();

        // Then
        verify(pipeline).initialize(ENGLISH);
        verify(pipeline).terminate(ENGLISH);
        verify(pipeline).initialize(FRENCH);
        verify(pipeline).terminate(FRENCH);
        ArgumentCaptor<Stream<String>> streamCaptor = ArgumentCaptor.forClass(Stream.class);
        verify(pipeline, times(2)).processText(streamCaptor.capture(), same(ENGLISH));
        verify(pipeline, times(2)).processText(streamCaptor.capture(), same(FRENCH));
        assertThat(streamCaptor.getAllValues().size()).isEqualTo(4);
    }
}
