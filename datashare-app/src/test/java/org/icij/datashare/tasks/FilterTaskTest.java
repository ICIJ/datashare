package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.RedisUserDocumentQueue;
import org.icij.extract.redis.RedisDocumentSet;
import org.junit.After;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.PropertiesProvider.QUEUE_NAME_OPTION;
import static org.icij.datashare.tasks.PipelineTask.POISON;
import static org.icij.datashare.user.User.local;

public class FilterTaskTest {
    private PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<String, String>() {{
        put("filterSet", "extract:filter");
        put(QUEUE_NAME_OPTION, "extract:test");
        put("redisAddress", "redis://redis:6379");
    }});
    private RedisUserDocumentQueue queue = new RedisUserDocumentQueue(propertiesProvider);
    private RedisDocumentSet set = new RedisDocumentSet("extract:filter","redis://redis:6379");

    @Test(expected = IllegalArgumentException.class)
    public void test_filter_with_no_filter() {
        new FilterTask(new PropertiesProvider(), local());
    }

    @Test
    public void test_filter_queue_removes_already_extracted_docs() throws Exception {
        queue.put(Paths.get("file:/path/to/doc"));
        queue.put(Paths.get("file:/path/to/extracted"));
        queue.put(POISON);
        set.add(Paths.get("file:/path/to/extracted"));
        FilterTask filterTask = new FilterTask(propertiesProvider, local());
        assertThat(filterTask.call()).isEqualTo(1);

        RedisUserDocumentQueue outputQueue = new RedisUserDocumentQueue(filterTask.getOutputQueueName(), propertiesProvider);
        assertThat(outputQueue.size()).isEqualTo(2);
        assertThat(outputQueue.take().toString()).isEqualTo("file:/path/to/doc");
    }

    @After
    public void tearDown() {
        queue.delete();
        set.delete();
        new RedisUserDocumentQueue("extract:test:filter", propertiesProvider).delete();
    }
}
