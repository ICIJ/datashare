package org.icij.datashare.text.nlp;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Message;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.junit.ClassRule;
import org.junit.Test;

import static org.icij.datashare.com.Message.Field.DOC_ID;
import static org.icij.datashare.com.Message.Type.EXTRACT_NLP;
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
}