package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.junit.Test;

import java.util.HashMap;

import static java.nio.file.Paths.get;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.user.User.local;

public class DeduplicateTaskTest {
    private PropertiesProvider propertyProvider = new PropertiesProvider(new HashMap<String, String>() {{
        put("queueName", "test:queue");
    }});
    DocumentCollectionFactory docCollectionFactory = new MemoryDocumentCollectionFactory();

    @Test
    public void test_filter_empty() throws Exception {
        assertThat(new DeduplicateTask(docCollectionFactory, new PropertiesProvider(), local(), "test:queue").call()).isEqualTo(0);
    }

    @Test
    public void test_filter_queue_removes_duplicates() throws Exception {
        docCollectionFactory.createQueue(propertyProvider, "test:queue").put(get("/path/to/doc"));
        docCollectionFactory.createQueue(propertyProvider, "test:queue").put(get("/path/to/doc"));

        assertThat(new DeduplicateTask(docCollectionFactory, propertyProvider, local(), "test:queue").call()).isEqualTo(1);
        assertThat(docCollectionFactory.createQueue(propertyProvider, "test:queue:deduplicate").size()).isEqualTo(1);
    }
}
