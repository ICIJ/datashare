package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.RedisUserDocumentQueue;
import org.junit.After;
import org.junit.Test;

import java.util.HashMap;

import static java.nio.file.Paths.get;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.user.User.local;

public class DeduplicateTaskTest {
    private PropertiesProvider propertyProvider = new PropertiesProvider(new HashMap<String, String>() {{
        put("redisAddress", "redis://redis:6379");
        put("queueName", "test:queue");
    }});
    private RedisUserDocumentQueue queue = new RedisUserDocumentQueue("test:queue", propertyProvider);

    @Test
    public void test_filter_empty() throws Exception {
        assertThat(new DeduplicateTask(new PropertiesProvider(), local(), "test:queue").call()).isEqualTo(0);
    }

    @Test
    public void test_filter_queue_removes_duplicates() throws Exception {
        queue.put(get("/path/to/doc"));
        queue.put(get("/path/to/doc"));

        assertThat(new DeduplicateTask(propertyProvider, local(), "test:queue").call()).isEqualTo(1);
        assertThat(new RedisUserDocumentQueue("test:queue:deduplicate", propertyProvider).size()).isEqualTo(1);
    }

    @After
    public void tearDown() {
        queue.delete();
        new RedisUserDocumentQueue("test:queue:deduplicate", propertyProvider).delete();
    }
}
