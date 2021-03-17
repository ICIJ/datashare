package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.user.User.local;
import static org.icij.datashare.user.User.nullUser;
import static org.mockito.Mockito.mock;

public class IndexTaskTest {
    @Test
    public void test_index_task_uses_users_index_name() {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);

        new IndexTask(spewer, mock(Publisher.class), mock(DocumentCollectionFactory.class), local(), "queueName", new PropertiesProvider(new HashMap<String, String>() {{
            put("redisAddress", "redis://redis:6379");
        }}).getProperties());

        Mockito.verify(spewer).withIndex("local-datashare");
    }
    @Test
    public void test_index_task_with_null_user_and_null_index_name() {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);

        new IndexTask(spewer, mock(Publisher.class), mock(DocumentCollectionFactory.class), local(), "queueName", new PropertiesProvider(new HashMap<String, String>() {{
            put("redisAddress", "redis://redis:6379");
        }}).getProperties());

        Mockito.verify(spewer).withIndex("local-datashare");
    }
    @Test
    public void test_index_task_null_user_uses_options_for_index_name() {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);

        new IndexTask(spewer, mock(Publisher.class), mock(DocumentCollectionFactory.class), nullUser(), "queueName", new PropertiesProvider(new HashMap<String, String>() {{
            put("redisAddress", "redis://redis:6379");
            put("defaultProject", "foo");
        }}).getProperties());

        Mockito.verify(spewer).withIndex("foo");
    }
    @Test
    public void test_index_queue_contains_poison_pill_without_scan_step_before() throws Exception {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);

        IndexTask indexTask = new IndexTask(spewer, mock(Publisher.class), new MemoryDocumentCollectionFactory(), local(), "queueName", new PropertiesProvider(new HashMap<String, String>() {{
            put("redisAddress", "redis://redis:6379");
        }}).getProperties());

        assertThat(indexTask.queue).contains(IndexTask.POISON).hasSize(1);
    }
}
