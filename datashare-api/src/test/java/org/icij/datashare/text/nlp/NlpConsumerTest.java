package org.icij.datashare.text.nlp;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Language.FRENCH;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class NlpConsumerTest {
    @Mock private Indexer indexer;
    @Mock private AbstractPipeline pipeline;
    private NlpConsumer nlpListener;

    @Before
    public void setUp() {
        initMocks(this);
        nlpListener = new NlpConsumer(pipeline, indexer,  null);
    }

    @Test
    public void test_on_message_does_nothing__when_doc_not_found_in_index() throws Exception {
        nlpListener.findNamedEntities("projectName","unknownId", "routing");

        verify(pipeline, never()).initialize(any(Language.class));
        verify(pipeline, never()).process(anyString(), anyString(), any(Language.class));
    }

    @Test
    public void test_on_message_do_not_processNLP__when_init_fails() throws Exception {
        when(pipeline.initialize(any())).thenReturn(false);
        when(indexer.get(anyString(), anyString(), anyString())).thenReturn(createDoc("content").build());

        nlpListener.findNamedEntities("projectName","id", "routing");
        verify(pipeline, never()).process(anyString(), anyString(), any());
    }

    @Test
    public void test_on_message_processNLP__when_doc_found_in_index() throws Exception {
        when(pipeline.initialize(any())).thenReturn(true);
        Document doc = createDoc("content").build();
        when(pipeline.process(anyString(), anyString(), any())).thenReturn(new Annotations(doc.getId(), Pipeline.Type.MITIE, FRENCH));
        when(indexer.get("projectName", doc.getId(), "routing")).thenReturn(doc);

        nlpListener.findNamedEntities("projectName", doc.getId(), "routing");

        verify(pipeline).initialize(FRENCH);
        verify(pipeline).process("content", doc.getId(), FRENCH);
    }
}
