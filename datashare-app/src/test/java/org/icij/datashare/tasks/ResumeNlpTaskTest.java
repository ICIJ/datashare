package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;

import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEXES;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

public class ResumeNlpTaskTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(IMMEDIATE);

    @After public void tearDown() throws IOException { es.removeAll();}

    @Test
    public void test_bug_size_of_search() throws Exception {
        for (int i = 0; i < 20; i++) {
            indexer.add(TEST_INDEXES[0], createDoc("doc" + i).with(Pipeline.Type.CORENLP).build());
        }
        Publisher publisher = mock(Publisher.class);
        ResumeNlpTask resumeNlpTask = new ResumeNlpTask(publisher, indexer,
                new PropertiesProvider(new HashMap<String, String>() {{
                    put("defaultProject", "test-datashare");
                }}), new User("test"), new HashSet<Pipeline.Type>() {{add(Pipeline.Type.OPENNLP);}});
        resumeNlpTask.call();
        verify(publisher, times(22)).publish(any(), any());
    }
}
