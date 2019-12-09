package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.OptionsWrapper;
import org.icij.datashare.extract.RedisUserDocumentQueue;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.extract.document.DigestIdentifier;
import org.icij.extract.document.DocumentFactory;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;

import static java.nio.file.Paths.get;
import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.user.User.local;

public class FilterTaskTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private PropertiesProvider propertyProviader = new PropertiesProvider(new HashMap<String, String>() {{
        put("defaultProject", TEST_INDEX);
        put("redisAddress", "redis://redis:6379");
    }});
    private ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(IMMEDIATE);
    private DocumentFactory documentFactory = new DocumentFactory().withIdentifier(new DigestIdentifier("sha256", Charset.defaultCharset()));
    private RedisUserDocumentQueue queue = new RedisUserDocumentQueue(local(),
            new OptionsWrapper(new HashMap<String, String>() {{
                put("redisAddress", "redis://redis:6379");
            }}).asOptions());

    @After
    public void tearDown() throws IOException {
        queue.delete();
        es.removeAll();
    }

    @Test
    public void test_filter_empty() throws Exception {
        assertThat(new FilterTask(indexer, new PropertiesProvider(), local()).call()).isEqualTo(0);
    }

    @Test
    public void test_filter_queue_removes_duplicates() throws Exception {
        queue.put(documentFactory.create("/path/to/doc"));
        queue.put(documentFactory.create("/path/to/doc"));

        assertThat(new FilterTask(indexer, propertyProviader, local()).call()).isEqualTo(1);
        assertThat(queue.size()).isEqualTo(1);
    }

    @Test
    public void test_filter_queue_removes_already_extracted_docs_and_sums_with_duplicates() throws Exception {
        indexer.add(TEST_INDEX, createDoc("id").with(get("/path/to/extracted")).build());
        queue.put(documentFactory.create("file:/path/to/doc"));
        queue.put(documentFactory.create("file:/path/to/doc"));
        queue.put(documentFactory.create("file:/path/to/extracted"));

        assertThat(new FilterTask(indexer, propertyProviader, local()).call()).isEqualTo(2);
        assertThat(queue.size()).isEqualTo(1);
    }
}
