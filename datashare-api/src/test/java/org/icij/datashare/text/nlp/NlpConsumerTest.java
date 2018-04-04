package org.icij.datashare.text.nlp;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.HashMap;

import static org.icij.datashare.text.Language.FRENCH;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class NlpConsumerTest {
    @Mock private Indexer indexer;
    @Mock private AbstractPipeline pipeline;
    private NlpDatashareConsumer nlpListener;

    @Before
    public void setUp() {
        initMocks(this);
        nlpListener = new NlpDatashareConsumer(pipeline, indexer,  null);
    }

    @Test
    public void test_on_message_does_nothing__when_doc_not_found_in_index() throws Exception {
        nlpListener.extractNamedEntities("unknownId", "routing");

        verify(pipeline, never()).initialize(any(Language.class));
        verify(pipeline, never()).process(anyString(), anyString(), any(Language.class));
    }

    @Test
    public void test_on_message_do_not_processNLP__when_init_fails() throws Exception {
        when(pipeline.initialize(any())).thenReturn(false);
        Document doc = new Document(Paths.get("/path/to/doc"), "content", FRENCH,
                Charset.defaultCharset(), "test/plain", new HashMap<>(), Document.Status.INDEXED);
        when(indexer.get(anyString(), anyString())).thenReturn(doc);

        nlpListener.extractNamedEntities("id", "routing");
        verify(pipeline, never()).process(anyString(), anyString(), any());
    }

    @Test
    public void test_on_message_processNLP__when_doc_found_in_index() throws Exception {
        when(pipeline.initialize(any())).thenReturn(true);
        Document doc = new Document(Paths.get("/path/to/doc"), "content", FRENCH,
                Charset.defaultCharset(), "test/plain", new HashMap<>(), Document.Status.INDEXED);
        when(pipeline.process(anyString(), anyString(), any())).thenReturn(new Annotations(doc.getId(), Pipeline.Type.MITIE, FRENCH));
        when(indexer.get(anyString(), anyString())).thenReturn(doc);

        nlpListener.extractNamedEntities("id", "routing");

        verify(pipeline).initialize(FRENCH);
        verify(pipeline).process("content", doc.getId(), FRENCH);
    }
}