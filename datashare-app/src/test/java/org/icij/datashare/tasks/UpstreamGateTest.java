package org.icij.datashare.tasks;

import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.asynctasks.TaskResult;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.queue.MemoryDocumentQueue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.PropertiesProvider.DEFAULT_QUEUE_CAPACITY;
import static org.icij.datashare.cli.DatashareCliOptions.POLLING_INTERVAL_SECONDS_OPT;
import static org.icij.datashare.tasks.PipelineTask.UPSTREAM_TASK_ID;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

/**
 * Covers the upstream-task gate: a consumer stage runs concurrently with its producer stage and
 * exits only when its input queue is empty AND the producer task is terminal.
 */
public class UpstreamGateTest {
    @Mock private Indexer indexer;
    @Mock private AbstractPipeline pipeline;
    private final MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();
    private final TaskRepositoryMemory taskRepository = new TaskRepositoryMemory();
    private final ExecutorService consumerExecutor = Executors.newSingleThreadExecutor();
    // counted down by the queue itself, so the test never has to sleep to know the consumer
    // has already polled an empty queue: that is the exact moment the gate has to hold it
    private final CountDownLatch firstEmptyPoll = new CountDownLatch(1);

    @Before
    public void setUp() {
        initMocks(this);
    }

    @After
    public void tearDown() {
        consumerExecutor.shutdownNow();
    }

    @Test(timeout = 30000)
    public void test_consumer_drains_entries_enqueued_after_it_started_and_returns_when_upstream_is_done() throws Exception {
        Task<Long> upstream = runningUpstreamTask();
        DocumentQueue<String> queue = nlpQueueSignallingEmptyPolls();
        ExtractNlpTask nlpTask = nlpTaskGatedOn(upstream);

        Future<Long> consumed = consumerExecutor.submit((Callable<Long>) nlpTask::call);
        // without the gate the consumer has already returned 0 by the time this await comes back
        assertThat(firstEmptyPoll.await(20, SECONDS)).isTrue();
        queue.add("docId1");
        queue.add("docId2");
        upstream.setResult(new TaskResult<>(0L));

        assertThat(consumed.get(20, SECONDS)).isEqualTo(2L);
        verify(indexer).get("local-datashare", "docId1", "docId1");
        verify(indexer).get("local-datashare", "docId2", "docId2");
    }

    @Test(timeout = 10000)
    public void test_consumer_returns_immediately_when_upstream_is_already_terminal() throws Exception {
        Task<Long> upstream = runningUpstreamTask();
        upstream.setResult(new TaskResult<>(0L));

        assertThat(nlpTaskGatedOn(upstream).call()).isEqualTo(0L);
    }

    private Task<Long> runningUpstreamTask() throws Exception {
        Task<Long> upstream = new Task<>(EnqueueFromIndexTask.class.getName(), User.local(), Map.of());
        upstream.setState(Task.State.RUNNING);
        taskRepository.insert(upstream, null);
        return upstream;
    }

    private ExtractNlpTask nlpTaskGatedOn(Task<Long> upstream) {
        // 0.05s keeps the wait loop short: the assertions wait on a latch or on the task's return
        Map<String, Object> args = Map.of(UPSTREAM_TASK_ID, upstream.id, POLLING_INTERVAL_SECONDS_OPT, "0.05");
        return new ExtractNlpTask(indexer, pipeline, factory, taskRepository,
                new Task<>(ExtractNlpTask.class.getName(), User.local(), args), progress -> null);
    }

    private DocumentQueue<String> nlpQueueSignallingEmptyPolls() {
        String queueName = new PipelineHelper(new PropertiesProvider()).getQueueNameFor(Stage.NLP);
        DocumentQueue<String> queue = new MemoryDocumentQueue<>(queueName, DEFAULT_QUEUE_CAPACITY) {
            @Override
            public String poll() {
                String polled = super.poll();
                if (polled == null) {
                    firstEmptyPoll.countDown();
                }
                return polled;
            }
        };
        // pre-seed the factory: the task resolves its input queue by name in its constructor
        factory.queues.put(queueName, queue);
        return queue;
    }
}
