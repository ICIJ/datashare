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
import java.util.Optional;

import static org.icij.datashare.com.Message.Field.DOC_ID;
import static org.icij.datashare.com.Message.Type.EXTRACT_NLP;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.Language.FRENCH;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

public class NLPDatashareEventListenerTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();

    @Test
    public void test_on_message_does_nothing__when_doc_not_found_in_index() throws Exception {
        PropertiesProvider provider = new PropertiesProvider();
        AbstractPipeline pipeline = mock(AbstractPipeline.class);
        NLPDatashareEventListener nlpDatashareEventListener = new NLPDatashareEventListener(
                provider, pipeline, new ElasticsearchIndexer(es.client, provider));

        nlpDatashareEventListener.onMessage(new Message(EXTRACT_NLP).add(DOC_ID, "unknown"));

        verify(pipeline, never()).initialize(any(Language.class));
        verify(pipeline, never()).process(anyString(), anyString(), any(Language.class));
    }

    @Test
    public void test_on_message_processNLP__when_doc_found_in_index() throws Exception {
        PropertiesProvider provider = new PropertiesProvider();
        AbstractPipeline pipeline = mock(AbstractPipeline.class);
        ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, provider);
        Optional<Document> doc = Document.create(Paths.get("/path/to/doc"), "content", FRENCH,
                Charset.defaultCharset(), "test/plain", new HashMap<String, String>());
        indexer.add(TEST_INDEX, doc.get());
        NLPDatashareEventListener nlpDatashareEventListener = new NLPDatashareEventListener(provider, pipeline, indexer);

        nlpDatashareEventListener.onMessage(new Message(EXTRACT_NLP).add(DOC_ID, doc.get().getId()));

        verify(pipeline).initialize(FRENCH);
        verify(pipeline).process("content", doc.get().getId(), FRENCH);
    }
}