package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.extract.queue.DocumentQueue;
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
    }});
    DocumentCollectionFactory docCollectionFactory = new MemoryDocumentCollectionFactory();

    @Test(expected = IllegalArgumentException.class)
    public void test_filter_with_no_filter() {
        new FilterTask(docCollectionFactory, new PropertiesProvider(), local(), "queueName");
    }

    @Test
    public void test_filter_queue_removes_already_extracted_docs() throws Exception {
        docCollectionFactory.createQueue(propertiesProvider, "extract:test").put(Paths.get("file:/path/to/doc"));
        docCollectionFactory.createQueue(propertiesProvider, "extract:test").put(Paths.get("file:/path/to/extracted"));
        docCollectionFactory.createQueue(propertiesProvider, "extract:test").put(POISON);
        docCollectionFactory.createSet(propertiesProvider, "extract:filter").add(Paths.get("file:/path/to/extracted"));

        FilterTask filterTask = new FilterTask(docCollectionFactory, propertiesProvider, local(), "extract:test");
        assertThat(filterTask.call()).isEqualTo(1);

        DocumentQueue outputQueue = docCollectionFactory.createQueue(propertiesProvider, filterTask.getOutputQueueName());
        assertThat(outputQueue.size()).isEqualTo(2);
        assertThat(outputQueue.take().toString()).isEqualTo("file:/path/to/doc");
    }
}
