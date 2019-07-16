package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;

import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.Document.Status.DONE;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.Project.project;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

public class ResumeNlpTaskTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(IMMEDIATE);

    @After public void tearDown() throws IOException { es.removeAll();}

    @Test
    public void test_bug_size_of_search() throws Exception {
        for (int i = 0; i < 20; i++) {
            indexer.add(TEST_INDEX, createDoc("doc" + i));
        }
        Publisher publisher = mock(Publisher.class);
        ResumeNlpTask resumeNlpTask = new ResumeNlpTask(publisher, indexer,
                new PropertiesProvider(new HashMap<String, String>() {{ put("nlpPipelines", "OPENNLP");}}), new User("test"));
        resumeNlpTask.call();
        verify(publisher, times(22)).publish(any(), any());
    }

    private Document createDoc(String name) {
            return new Document(project("prj"), name, Paths.get("/path/to/").resolve(name), "content " + name,
                    FRENCH, Charset.defaultCharset(),
                    "text/plain", new HashMap<>(), DONE,
                    new HashSet<>(), new Date(), null, null,
                    0, 123L);
        }
}
