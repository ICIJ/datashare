package org.icij.datashare.tasks;

import junit.framework.TestCase;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;

public class ScanTaskTest extends TestCase {
    private final MemoryDocumentCollectionFactory<Path> documentCollectionFactory = new MemoryDocumentCollectionFactory<>();

    public void test_scan() throws Exception {
        assertThat(new ScanTask(documentCollectionFactory, User.local(),
                Paths.get(ClassLoader.getSystemResource("docs").getPath()), new PropertiesProvider().getProperties()).call()).isEqualTo(3);
        DocumentQueue<Path> queue = documentCollectionFactory.createQueue("extract:queue:index", Path.class);
        assertThat(queue.size()).isEqualTo(4); // with POISON
    }

    public void test_scan_with_queue_name() throws Exception {
        PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
            put("queueName", "foo");
        }});
        assertThat(new ScanTask(documentCollectionFactory, User.local(),
                Paths.get(ClassLoader.getSystemResource("docs").getPath()), propertiesProvider.getProperties()).call()).isEqualTo(3);
        DocumentQueue<Path> queue = documentCollectionFactory.createQueue("foo:index", Path.class);
        assertThat(queue.size()).isEqualTo(4); // with POISON
    }
}