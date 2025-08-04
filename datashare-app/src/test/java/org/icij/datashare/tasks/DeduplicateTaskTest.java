package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.junit.Test;

import java.nio.file.Path;
import java.util.Map;

import static java.nio.file.Paths.get;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.tasks.PipelineTask.PATH_POISON;

public class DeduplicateTaskTest {
    DocumentCollectionFactory<Path> docCollectionFactory = new MemoryDocumentCollectionFactory<>();
    Map<String, Object> defaultOpts = Map.of("queueName", "test:queue", "stages", "DEDUPLICATE");
    DeduplicateTask task = new DeduplicateTask(docCollectionFactory, new Task(DeduplicateTask.class.getName(), User.local(), defaultOpts), null);

    @Test(timeout = 2000)
    public void test_filter_empty() throws Exception {
        docCollectionFactory.createQueue("test:queue:deduplicate", Path.class).add(PATH_POISON);
        assertThat(new DeduplicateTask(docCollectionFactory, new Task(DeduplicateTask.class.getName(), User.local(), defaultOpts), null).call().value()).isEqualTo(0L);
    }

    @Test(timeout = 2000)
    public void test_filter_queue_removes_duplicates() throws Exception {
        docCollectionFactory.createQueue("test:queue:deduplicate", Path.class).put(get("/path/to/doc"));
        docCollectionFactory.createQueue("test:queue:deduplicate", Path.class).put(get("/path/to/doc"));
        docCollectionFactory.createQueue("test:queue:deduplicate", Path.class).add(PATH_POISON);

        assertThat(new DeduplicateTask(docCollectionFactory,  new Task(DeduplicateTask.class.getName(), User.local(), defaultOpts), null).call().value()).isEqualTo(1L);

        assertThat(docCollectionFactory.createQueue("test:queue:index", Path.class).size()).isEqualTo(2); // with POISON
    }

    @Test(timeout = 2000)
    public void test_pipeline_task_transfer_to_output_queue() throws Exception {
        task.inputQueue.put(get("/path/to/doc1"));
        task.inputQueue.put(get("/path/to/doc2"));
        task.inputQueue.put(PATH_POISON);

        task.transferToOutputQueue();

        assertThat(task.inputQueue.isEmpty()).isTrue();
        DocumentQueue<Path> outputQueue = docCollectionFactory.createQueue(task.getOutputQueueName(), Path.class);
        assertThat(outputQueue.size()).isEqualTo(3);
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc1");
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc2");
        assertThat(outputQueue.poll().toString()).isEqualTo(PATH_POISON.toString());
    }

    @Test(timeout = 2000)
    public void test_pipeline_task_conditional_transfer_to_output_queue() throws Exception {
        task.inputQueue.put(get("/path/to/doc1"));
        task.inputQueue.put(get("/path/to/doc2"));
        task.inputQueue.put(PATH_POISON);

        task.transferToOutputQueue(p -> p.toString().contains("1"));

        assertThat(task.inputQueue.isEmpty()).isTrue();
        DocumentQueue<Path> outputQueue = docCollectionFactory.createQueue(task.getOutputQueueName(), Path.class);
        assertThat(outputQueue.size()).isEqualTo(2);
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc1");
        assertThat(outputQueue.poll().toString()).isEqualTo(PATH_POISON.toString());
    }
}
