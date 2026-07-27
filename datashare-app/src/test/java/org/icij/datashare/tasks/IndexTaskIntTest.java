package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepository;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.asynctasks.TaskResult;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.test.LogbackCapturingRule;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.icij.datashare.user.User;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.queue.MemoryDocumentQueue;
import org.icij.spewer.FieldNames;
import org.icij.task.Options;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;
import org.slf4j.event.Level;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.PropertiesProvider.DEFAULT_QUEUE_CAPACITY;
import static org.icij.datashare.cli.DatashareCliOptions.POLLING_INTERVAL_SECONDS_OPT;
import static org.mockito.Mockito.verify;

public class IndexTaskIntTest {
    @Rule public ElasticsearchRule es = new ElasticsearchRule();
    @Rule public LogbackCapturingRule logback = new LogbackCapturingRule();

    static final List<Extractor> CREATED_EXTRACTORS = new ArrayList<>();

    static class ClosingProbeIndexTask extends IndexTask {
        ClosingProbeIndexTask(ElasticsearchSpewer spewer, DocumentCollectionFactory<Path> factory,
                              TaskRepository taskRepository, Task<Long> task, Function<Double, Void> cb) throws IOException {
            super(spewer, factory, taskRepository, task, cb);
        }
        @Override
        protected Extractor createExtractor(DocumentFactory documentFactory, Options<String> options) {
            Extractor spy = Mockito.spy(super.createExtractor(documentFactory, options));
            CREATED_EXTRACTORS.add(spy);
            return spy;
        }
    }

    private final MemoryDocumentCollectionFactory<Path> inputQueueFactory = new MemoryDocumentCollectionFactory<>();
    private final TaskRepositoryMemory taskRepository = new TaskRepositoryMemory();
    private final MemoryDocumentCollectionFactory<String> outputQueueFactory = new MemoryDocumentCollectionFactory<>();
    private Map<String, Object> map = new HashMap<>() {{
        put("defaultProject", es.getIndexName());
        put("queueName", "test:queue");
    }};
    private final PropertiesProvider propertiesProvider = new PropertiesProvider(map);
    private final ElasticsearchSpewer spewer = new ElasticsearchSpewer(new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True),
            outputQueueFactory, text -> Language.ENGLISH, new FieldNames(), propertiesProvider);

    @Test
    public void index_task_should_enqueue_indexed_doc_ids() throws Exception {
        DocumentQueue<Path> queue = inputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getQueueNameFor(Stage.INDEX), Path.class);
        queue.add(Paths.get(ClassLoader.getSystemResource("docs/doc.txt").getPath()));

        Long nbDocs = new IndexTask(spewer, inputQueueFactory, taskRepository, new Task<>(IndexTask.class.getName(), User.local(), map), null).call();

        assertThat(nbDocs).isEqualTo(1);
        DocumentQueue<String> outputQueue = outputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getOutputQueueNameFor(Stage.INDEX), String.class);
        assertThat(outputQueue).hasSize(1);
        assertThat(outputQueue.poll()).isEqualTo("bc6852541ef5200206a7a9740f3d2d62178a1f53b1aa5417ab426c6ec1f7cbc7");
    }

    @Test
    public void index_task_skips_a_legacy_poison_entry_at_the_head_of_the_queue() throws Exception {
        DocumentQueue<Path> queue = inputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getQueueNameFor(Stage.INDEX), Path.class);
        // a sentinel written by a pre-21.16 run sits FIRST: the drain must skip it and keep going,
        // where the old drain(PATH_POISON) would have stopped here and indexed nothing
        queue.add(Paths.get("POISON"));
        queue.add(Paths.get(ClassLoader.getSystemResource("docs/doc.txt").getPath()));

        Long nbDocs = new IndexTask(spewer, inputQueueFactory, taskRepository, new Task<>(IndexTask.class.getName(), User.local(), map), null).call();

        // the sentinel is not a document: the returned count must be 1, not 2
        assertThat(nbDocs).isEqualTo(1);
        DocumentQueue<String> outputQueue = outputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getOutputQueueNameFor(Stage.INDEX), String.class);
        assertThat(outputQueue).contains("bc6852541ef5200206a7a9740f3d2d62178a1f53b1aa5417ab426c6ec1f7cbc7");
        assertThat(logback.logs(Level.WARN)).contains("skipping legacy POISON entry in queue test:queue:index");
    }

    @Test
    public void index_task_update_progress() throws Exception {
        List<Double> progressValues = Collections.synchronizedList(new ArrayList<>());
        Function<Double, Void> callback = progress -> {
            progressValues.add(progress);
            return null;
        };

        DocumentQueue<Path> inputQueue = inputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getQueueNameFor(Stage.INDEX), Path.class);
        inputQueue.add(Paths.get(ClassLoader.getSystemResource("docs/doc.txt").getPath()));
        inputQueue.add(Paths.get(ClassLoader.getSystemResource("docs/embedded_doc.eml").getPath()));
        inputQueue.add(Paths.get(ClassLoader.getSystemResource("docs/foo/bar.txt").getPath()));

        IndexTask indexTask = new IndexTask(spewer, inputQueueFactory, taskRepository, new Task<>(IndexTask.class.getName(), User.local(), map), callback);
        indexTask.call();
        assertThat(progressValues.size()).isGreaterThan(1);
        assertThat(progressValues.get(0)).isLessThan(progressValues.get(progressValues.size() - 1));
        assertThat(progressValues).contains(0.5);
    }

    @Test(timeout = 30000)
    public void index_task_drains_a_queue_filled_while_the_upstream_task_is_running() throws Exception {
        Task<Long> upstream = new Task<>(ScanTask.class.getName(), User.local(), Map.of());
        upstream.setState(Task.State.RUNNING);
        taskRepository.insert(upstream, null);
        // counted down by the queue itself, so the test knows the drainer has already polled an
        // empty queue, which is the exact moment the latch has to keep it polling
        CountDownLatch firstEmptyPoll = new CountDownLatch(1);
        String queueName = new PipelineHelper(propertiesProvider).getQueueNameFor(Stage.INDEX);
        DocumentQueue<Path> queue = new MemoryDocumentQueue<>(queueName, DEFAULT_QUEUE_CAPACITY) {
            @Override
            public Path poll() {
                Path polled = super.poll();
                if (polled == null) {
                    firstEmptyPoll.countDown();
                }
                return polled;
            }
        };
        inputQueueFactory.queues.put(queueName, queue);
        Map<String, Object> args = new HashMap<>(map);
        args.put(PipelineTask.UPSTREAM_TASK_ID, upstream.id);
        args.put(POLLING_INTERVAL_SECONDS_OPT, "0.05");
        IndexTask indexTask = new IndexTask(spewer, inputQueueFactory, taskRepository,
                new Task<>(IndexTask.class.getName(), User.local(), args), null);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Long> indexed = executor.submit((Callable<Long>) indexTask::call);
            // without the latch the drainer has already stopped by the time this await comes back
            assertThat(firstEmptyPoll.await(20, TimeUnit.SECONDS)).isTrue();
            queue.add(Paths.get(ClassLoader.getSystemResource("docs/doc.txt").getPath()));
            upstream.setResult(new TaskResult<>(0L));

            assertThat(indexed.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void index_task_closes_extractor_after_run() throws Exception {
        CREATED_EXTRACTORS.clear();
        DocumentQueue<Path> inputQueue = inputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getQueueNameFor(Stage.INDEX), Path.class);
        inputQueue.add(Paths.get(ClassLoader.getSystemResource("docs/doc.txt").getPath()));

        IndexTask indexTask = new ClosingProbeIndexTask(spewer, inputQueueFactory, taskRepository, new Task<>(IndexTask.class.getName(), User.local(), map), null);
        indexTask.call();

        assertThat(CREATED_EXTRACTORS.size()).isEqualTo(1);
        verify(CREATED_EXTRACTORS.get(0)).close();
    }
}
