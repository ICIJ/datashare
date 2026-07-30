package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.asynctasks.TaskResult;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.queue.MemoryDocumentQueue;
import org.junit.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.nio.file.Paths.get;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.PropertiesProvider.DEFAULT_QUEUE_CAPACITY;

public class DeduplicateTaskTest {
    private final TaskRepositoryMemory taskRepository = new TaskRepositoryMemory();
    MemoryDocumentCollectionFactory<Path> docCollectionFactory = new MemoryDocumentCollectionFactory<>();
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

        task.transferToOutputQueue(p -> true);

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

    @Test(timeout = 30000)
    public void test_a_duplicate_enqueued_after_the_drain_started_is_dropped() throws Exception {
        Task<Long> scan = new Task<>(ScanTask.class.getName(), User.local(), Map.of());
        scan.setState(Task.State.RUNNING);
        taskRepository.insert(scan, null);
        // counted down by the queue itself, so the test never sleeps to know the drain has already
        // polled an empty queue: that is the moment the old upfront removeDuplicates() is behind us
        CountDownLatch firstEmptyPoll = new CountDownLatch(1);
        DocumentQueue<Path> queue = new MemoryDocumentQueue<>("test:queue:deduplicate", DEFAULT_QUEUE_CAPACITY) {
            @Override
            public Path poll() {
                Path polled = super.poll();
                if (polled == null) {
                    firstEmptyPoll.countDown();
                }
                return polled;
            }
        };
        // pre-seed the factory: the task resolves its input queue by name in its constructor
        docCollectionFactory.queues.put("test:queue:deduplicate", queue);
        queue.put(get("/path/to/doc"));
        Map<String, Object> args = Map.of("queueName", "test:queue", "stages", "DEDUPLICATE",
                UpstreamGate.UPSTREAM_TASK_ID, scan.id);
        DeduplicateTask task = new DeduplicateTask(docCollectionFactory, new UpstreamGate.Factory(taskRepository),
                new Task<>(DeduplicateTask.class.getName(), User.local(), args), null);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Long> dropped = executor.submit((Callable<Long>) task::call);
            assertThat(firstEmptyPoll.await(20, SECONDS)).isTrue();
            queue.put(get("/path/to/doc")); // the duplicate SCAN enqueues mid-drain
            scan.setResult(new TaskResult<>(0L));

            assertThat(dropped.get(20, SECONDS)).isEqualTo(1L);
            assertThat(docCollectionFactory.createQueue("test:queue:index", Path.class).size()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }
}
