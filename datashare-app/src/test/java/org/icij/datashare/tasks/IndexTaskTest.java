package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.icij.task.Options;
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

        new IndexTask(spewer, mock(DocumentCollectionFactory.class), local(), new PropertiesProvider(new HashMap<>() {{
            put("redisAddress", "redis://redis:6379");
            put("queueName", "test:queue");
        }}).getProperties());

        Mockito.verify(spewer).withIndex("local-datashare");
    }
    @Test
    public void test_index_task_with_null_user_and_null_index_name() {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);

        new IndexTask(spewer, mock(DocumentCollectionFactory.class), local(), new PropertiesProvider(new HashMap<>() {{
            put("redisAddress", "redis://redis:6379");
            put("queueName", "test:queue");
        }}).getProperties());

        Mockito.verify(spewer).withIndex("local-datashare");
    }
    @Test
    public void test_index_task_null_user_uses_options_for_index_name() {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);

        new IndexTask(spewer, mock(DocumentCollectionFactory.class), nullUser(), new PropertiesProvider(new HashMap<>() {{
            put("redisAddress", "redis://redis:6379");
            put("defaultProject", "foo");
            put("queueName", "test:queue");
        }}).getProperties());

        Mockito.verify(spewer).withIndex("foo");
    }

    @Test
    public void test_options_include_ocr() {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);
        IndexTask indexTask = new IndexTask(spewer, mock(DocumentCollectionFactory.class), nullUser(), new PropertiesProvider(new HashMap<>(){{
            put("queueName", "test:queue");
        }}).getProperties());
        Options<String> options = indexTask.options();
        assertThat(options.toString()).contains("ocr=");
    }

    @Test
    public void test_options_include_ocr_language() {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);
        IndexTask indexTask = new IndexTask(spewer, mock(DocumentCollectionFactory.class), nullUser(), new PropertiesProvider(new HashMap<>() {{
            put("queueName", "test:queue");
        }}).getProperties());
        Options<String> options = indexTask.options();
        assertThat(options.toString()).contains("ocrLanguage=");
    }

    @Test
    public void test_options_include_language() {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);
        IndexTask indexTask = new IndexTask(spewer, mock(DocumentCollectionFactory.class), nullUser(), new PropertiesProvider(new HashMap<>() {{
            put("language", "FRENCH");
            put("queueName", "test:queue");
        }}).getProperties());
        Options<String> options = indexTask.options();
        assertThat(options.toString()).contains("language=");
    }
}
