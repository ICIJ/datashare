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
    Map<String, Object> defaultOpts = Map.of("queueName", "test:queue", "stages", "DEDUPLICATE", "queuePoll", "10ms");
    DeduplicateTask task = new DeduplicateTask(docCollectionFactory, new Task<>(DeduplicateTask.class.getName(), User.local(), defaultOpts), null);

    @Test(timeout = 2000)
    public void test_filter_empty() throws Exception {
        assertThat(new DeduplicateTask(docCollectionFactory, new Task<>(DeduplicateTask.class.getName(), User.local(), defaultOpts), null).call()).isEqualTo(0);
    }

    @Test(timeout = 2000)
    public void test_filter_queue_removes_duplicates() throws Exception {
        docCollectionFactory.createQueue("test:queue:deduplicate", Path.class).put(get("/path/to/doc"));
        docCollectionFactory.createQueue("test:queue:deduplicate", Path.class).put(get("/path/to/doc"));

        assertThat(new DeduplicateTask(docCollectionFactory, new Task<>(DeduplicateTask.class.getName(), User.local(), defaultOpts), null).call()).isEqualTo(1);

        assertThat(docCollectionFactory.createQueue("test:queue:index", Path.class).size()).isEqualTo(1); // no POISON
    }

    @Test(timeout = 2000)
    public void test_transfer_terminates_on_quiet_queue_without_poison() throws Exception {
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
    public void test_skips_legacy_poison_without_forwarding_it() throws Exception {
        task.inputQueue.put(get("/path/to/doc1"));
        task.inputQueue.put(PATH_POISON);
        task.inputQueue.put(get("/path/to/doc2"));

        task.transferToOutputQueue();

        DocumentQueue<Path> outputQueue = docCollectionFactory.createQueue(task.getOutputQueueName(), Path.class);
        assertThat(outputQueue.size()).isEqualTo(2); // POISON skipped, not forwarded
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc1");
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc2");
    }

    @Test(timeout = 2000)
    public void test_transfer_terminates_with_zero_queue_poll() throws Exception {
        // NB: queuePoll="0" clamps the internal poll timeout to 1ms (see Math.max(1L, ...) in
        // DeduplicateTask#transferToOutputQueue). MemoryDocumentCollectionFactory's ArrayBlockingQueue
        // returns immediately on poll(0, MS) regardless of the clamp, so this test cannot reproduce the
        // real Redis BLPOP-with-timeout-0 hang (which blocks forever). It only guards that the clamped
        // value is still accepted and the loop terminates on a quiet queue.
        Map<String, Object> zeroPollOpts = Map.of("queueName", "test:queue:zero", "stages", "DEDUPLICATE", "queuePoll", "0");
        DeduplicateTask zeroPollTask = new DeduplicateTask(docCollectionFactory, new Task<>(DeduplicateTask.class.getName(), User.local(), zeroPollOpts), null);

        zeroPollTask.transferToOutputQueue();

        DocumentQueue<Path> outputQueue = docCollectionFactory.createQueue(zeroPollTask.getOutputQueueName(), Path.class);
        assertThat(outputQueue.isEmpty()).isTrue();
    }

    @Test(timeout = 2000)
    public void test_conditional_transfer_to_output_queue() throws Exception {
        task.inputQueue.put(get("/path/to/doc1"));
        task.inputQueue.put(get("/path/to/doc2"));

        task.transferToOutputQueue(p -> p.toString().contains("1"));

        assertThat(task.inputQueue.isEmpty()).isTrue();
        DocumentQueue<Path> outputQueue = docCollectionFactory.createQueue(task.getOutputQueueName(), Path.class);
        assertThat(outputQueue.size()).isEqualTo(1);
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc1");
    }
}
