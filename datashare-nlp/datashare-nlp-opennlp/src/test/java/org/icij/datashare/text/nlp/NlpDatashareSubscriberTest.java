package org.icij.datashare.text.nlp;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Message;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.junit.ClassRule;
import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.HashMap;

import static org.icij.datashare.com.Message.Field.DOC_ID;
import static org.icij.datashare.com.Message.Type.EXTRACT_NLP;
import static org.icij.datashare.text.Language.FRENCH;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

public class NlpDatashareSubscriberTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private PropertiesProvider provider = new PropertiesProvider();
    private ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, provider);
    private AbstractPipeline pipeline = mock(AbstractPipeline.class);
    private NlpDatashareSubscriber nlpDatashareEventListener = new NlpDatashareSubscriber(pipeline, indexer,  provider.getProperties());

    @Test
    public void test_on_message_does_nothing__when_doc_not_found_in_index() throws Exception {
        nlpDatashareEventListener.onMessage(new Message(EXTRACT_NLP).add(DOC_ID, "unknown"));

        verify(pipeline, never()).initialize(any(Language.class));
        verify(pipeline, never()).process(anyString(), anyString(), any(Language.class));
    }

    @Test
    public void test_on_message_do_not_processNLP__when_init_fails() throws Exception {
        when(pipeline.initialize(any())).thenReturn(false);
        Document doc = new Document(Paths.get("/path/to/doc"), "content", FRENCH,
                        Charset.defaultCharset(), "test/plain", new HashMap<>(), Document.Status.INDEXED);
        indexer.add(doc);

        nlpDatashareEventListener.onMessage(new Message(EXTRACT_NLP).add(DOC_ID, doc.getId()));
        verify(pipeline, never()).process(anyString(), anyString(), any());
    }

    @Test
    public void test_on_message_processNLP__when_doc_found_in_index() throws Exception {
        when(pipeline.initialize(any())).thenReturn(true);
        Document doc = new Document(Paths.get("/path/to/doc"), "content", FRENCH,
                Charset.defaultCharset(), "test/plain", new HashMap<>(), Document.Status.INDEXED);
        indexer.add(doc);

        nlpDatashareEventListener.onMessage(new Message(EXTRACT_NLP).add(DOC_ID, doc.getId()));

        verify(pipeline).initialize(FRENCH);
        verify(pipeline).process("content", doc.getId(), FRENCH);
    }
}