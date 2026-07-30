package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.junit.Test;

import java.nio.file.Path;
import java.util.Map;

import static java.nio.file.Paths.get;
import static org.fest.assertions.Assertions.assertThat;

public class DeduplicateTaskTest {
    private final TaskRepositoryMemory taskRepository = new TaskRepositoryMemory();
    DocumentCollectionFactory<Path> docCollectionFactory = new MemoryDocumentCollectionFactory<>();
    Map<String, Object> defaultOpts = Map.of("queueName", "test:queue", "stages", "DEDUPLICATE");
    DeduplicateTask task = new DeduplicateTask(docCollectionFactory, new UpstreamGate.Factory(taskRepository), new Task<>(DeduplicateTask.class.getName(), User.local(), defaultOpts), null);

    @Test(timeout = 2000)
    public void test_filter_empty() throws Exception {
        assertThat(new DeduplicateTask(docCollectionFactory, new UpstreamGate.Factory(taskRepository), new Task<>(DeduplicateTask.class.getName(), User.local(), defaultOpts), null).call()).isEqualTo(0);
    }

    @Test(timeout = 2000)
    public void test_filter_queue_removes_duplicates() throws Exception {
        docCollectionFactory.createQueue("test:queue:deduplicate", Path.class).put(get("/path/to/doc"));
        docCollectionFactory.createQueue("test:queue:deduplicate", Path.class).put(get("/path/to/doc"));

        assertThat(new DeduplicateTask(docCollectionFactory, new UpstreamGate.Factory(taskRepository), new Task<>(DeduplicateTask.class.getName(), User.local(), defaultOpts), null).call()).isEqualTo(1);

        assertThat(docCollectionFactory.createQueue("test:queue:index", Path.class).size()).isEqualTo(1);
    }

    @Test(timeout = 2000)
    public void test_pipeline_task_transfer_to_output_queue() throws Exception {
        task.inputQueue.put(get("/path/to/doc1"));
        task.inputQueue.put(get("/path/to/doc2"));

        task.transferToOutputQueue();

        assertThat(task.inputQueue.isEmpty()).isTrue();
        DocumentQueue<Path> outputQueue = docCollectionFactory.createQueue(task.getOutputQueueName(), Path.class);
        assertThat(outputQueue.size()).isEqualTo(2);
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc1");
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc2");
    }

    @Test(timeout = 2000)
    public void test_pipeline_task_conditional_transfer_to_output_queue() throws Exception {
        task.inputQueue.put(get("/path/to/doc1"));
        task.inputQueue.put(get("/path/to/doc2"));

        task.transferToOutputQueue(p -> p.toString().contains("1"));

        assertThat(task.inputQueue.isEmpty()).isTrue();
        DocumentQueue<Path> outputQueue = docCollectionFactory.createQueue(task.getOutputQueueName(), Path.class);
        assertThat(outputQueue.size()).isEqualTo(1);
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc1");
    }
}
