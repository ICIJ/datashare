package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.junit.Test;

import java.nio.file.Path;
import java.util.HashMap;

import static java.nio.file.Paths.get;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.tasks.PipelineTask.PATH_POISON;
import static org.icij.datashare.user.User.local;

public class DeduplicateTaskTest {
    private final PropertiesProvider propertyProvider = new PropertiesProvider(new HashMap<>() {{
        put("queueName", "test:queue");
        put("stages", "DEDUPLICATE");
    }});
    DocumentCollectionFactory<Path> docCollectionFactory = new MemoryDocumentCollectionFactory<>();
    DeduplicateTask task = new DeduplicateTask(docCollectionFactory, propertyProvider, User.local());
    @Test(timeout = 2000)
    public void test_filter_empty() throws Exception {
        docCollectionFactory.createQueue("test:queue:deduplicate", Path.class).add(PATH_POISON);
        assertThat(new DeduplicateTask(docCollectionFactory, propertyProvider, local()).call()).isEqualTo(0);
    }

    @Test(timeout = 2000)
    public void test_filter_queue_removes_duplicates() throws Exception {
        docCollectionFactory.createQueue("test:queue:deduplicate", Path.class).put(get("/path/to/doc"));
        docCollectionFactory.createQueue("test:queue:deduplicate", Path.class).put(get("/path/to/doc"));
        docCollectionFactory.createQueue("test:queue:deduplicate", Path.class).add(PATH_POISON);

        assertThat(new DeduplicateTask(docCollectionFactory, propertyProvider, local()).call()).isEqualTo(1);

        assertThat(docCollectionFactory.createQueue("test:queue:index", Path.class).size()).isEqualTo(2); // with POISON
    }

    @Test(timeout = 2000)
    public void test_pipeline_task_transfer_to_output_queue() throws Exception {
        task.queue.put(get("/path/to/doc1"));
        task.queue.put(get("/path/to/doc2"));
        task.queue.put(PATH_POISON);

        task.transferToOutputQueue();

        assertThat(task.queue.isEmpty()).isTrue();
        DocumentQueue<Path> outputQueue = docCollectionFactory.createQueue(task.getOutputQueueName(), Path.class);
        assertThat(outputQueue.size()).isEqualTo(3);
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc1");
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc2");
        assertThat(outputQueue.poll().toString()).isEqualTo(PATH_POISON.toString());
    }

    @Test(timeout = 2000)
    public void test_pipeline_task_conditional_transfer_to_output_queue() throws Exception {
        task.queue.put(get("/path/to/doc1"));
        task.queue.put(get("/path/to/doc2"));
        task.queue.put(PATH_POISON);

        task.transferToOutputQueue(p -> p.toString().contains("1"));

        assertThat(task.queue.isEmpty()).isTrue();
        DocumentQueue<Path> outputQueue = docCollectionFactory.createQueue(task.getOutputQueueName(), Path.class);
        assertThat(outputQueue.size()).isEqualTo(2);
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc1");
        assertThat(outputQueue.poll().toString()).isEqualTo(PATH_POISON.toString());
    }
}
