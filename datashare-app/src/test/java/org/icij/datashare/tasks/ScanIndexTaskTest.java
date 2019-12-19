package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.RedisUserDocumentQueue;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;
import redis.clients.jedis.Jedis;

import java.util.HashMap;
import java.util.Set;

import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.PropertiesProvider.fromMap;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;

public class ScanIndexTaskTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<String, String>() {{
        put("defaultProject", TEST_INDEX);
        put("redisAddress", "redis://redis:6379");
    }});
    private ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(IMMEDIATE);
    private RedisUserDocumentQueue queue = new RedisUserDocumentQueue(propertiesProvider);

    @Test
    public void test_empty_index() throws Exception {
        assertThat(new ScanIndexTask(indexer, propertiesProvider, User.nullUser()).call()).isEqualTo(0);
    }

    @Test
    public void test_transfer_indexed_paths_to_queue() throws Exception {
        indexer.add(TEST_INDEX, DocumentBuilder.createDoc("id1").build());
        indexer.add(TEST_INDEX, DocumentBuilder.createDoc("id2").build());

        assertThat(new ScanIndexTask(indexer, propertiesProvider, User.nullUser()).call()).isEqualTo(2);

        assertThat(queue.size()).isEqualTo(2);
        assertThat(queue.poll().toString()).isEqualTo("file:/path/to/id2");
        assertThat(queue.poll().toString()).isEqualTo("file:/path/to/id1");
    }

    @Test
    public void test_transfer_indexed_paths_to_filter_set() throws Exception {
        indexer.add(TEST_INDEX, DocumentBuilder.createDoc("id1").build());
        indexer.add(TEST_INDEX, DocumentBuilder.createDoc("id2").build());
        propertiesProvider.mergeWith(fromMap(new HashMap<String, String>() {{
            put("filterSet", "extract:filter");
        }}));

        assertThat(new ScanIndexTask(indexer, propertiesProvider, User.nullUser()).call()).isEqualTo(2);

        assertThat(queue.size()).isEqualTo(0);
        Set<String> actualSet = new Jedis("redis").smembers("extract:filter");
        assertThat(actualSet).contains("file:/path/to/id1", "file:/path/to/id2");
    }

    @After
    public void tearDown() {
        queue.delete();
    }
}
