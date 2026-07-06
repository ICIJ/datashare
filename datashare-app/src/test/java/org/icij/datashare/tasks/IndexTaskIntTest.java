package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.icij.datashare.user.User;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.queue.DocumentQueue;
import org.icij.spewer.FieldNames;
import org.icij.task.Options;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.verify;

public class IndexTaskIntTest {
    @Rule public ElasticsearchRule es = new ElasticsearchRule();

    static final List<Extractor> CREATED_EXTRACTORS = new ArrayList<>();

    static class ClosingProbeIndexTask extends IndexTask {
        ClosingProbeIndexTask(ElasticsearchSpewer spewer, DocumentCollectionFactory<Path> factory,
                              Task<Long> task, Function<Double, Void> cb) throws IOException {
            super(spewer, factory, task, cb);
        }
        @Override
        protected Extractor createExtractor(DocumentFactory documentFactory, Options<String> options) {
            Extractor spy = Mockito.spy(super.createExtractor(documentFactory, options));
            CREATED_EXTRACTORS.add(spy);
            return spy;
        }
    }

    private final MemoryDocumentCollectionFactory<Path> inputQueueFactory = new MemoryDocumentCollectionFactory<>();
    private final MemoryDocumentCollectionFactory<String> outputQueueFactory = new MemoryDocumentCollectionFactory<>();
    private Map<String, Object> map = new HashMap<>() {{
        put("defaultProject", es.getIndexName());
        put("queueName", "test:queue");
        put("queuePoll", "0"); // pre-populated queue: drain then stop immediately
    }};
    private final PropertiesProvider propertiesProvider = new PropertiesProvider(map);
    private final ElasticsearchSpewer spewer = new ElasticsearchSpewer(new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True),
            outputQueueFactory, text -> Language.ENGLISH, new FieldNames(), propertiesProvider);

    @Test
    public void index_task_forwards_only_document_ids_without_poison() throws Exception {
        DocumentQueue<Path> queue = inputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getQueueNameFor(Stage.INDEX), Path.class);
        queue.add(Paths.get(ClassLoader.getSystemResource("docs/doc.txt").getPath()));

        Long nbDocs = new IndexTask(spewer, inputQueueFactory, new Task<>(IndexTask.class.getName(), User.local(), map), null).call();

        assertThat(nbDocs).isEqualTo(1);
        DocumentQueue<String> outputQueue = outputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getOutputQueueNameFor(Stage.INDEX), String.class);
        assertThat(outputQueue).hasSize(1);
        assertThat(outputQueue.poll()).isEqualTo("bc6852541ef5200206a7a9740f3d2d62178a1f53b1aa5417ab426c6ec1f7cbc7");
    }

    @Test
    public void index_task_skips_legacy_poison_in_input_queue() throws Exception {
        DocumentQueue<Path> queue = inputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getQueueNameFor(Stage.INDEX), Path.class);
        queue.add(PipelineTask.PATH_POISON);
        queue.add(Paths.get(ClassLoader.getSystemResource("docs/doc.txt").getPath()));
        queue.add(PipelineTask.PATH_POISON);

        new IndexTask(spewer, inputQueueFactory, new Task<>(IndexTask.class.getName(), User.local(), map), null).call();

        DocumentQueue<String> outputQueue = outputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getOutputQueueNameFor(Stage.INDEX), String.class);
        assertThat(outputQueue).hasSize(1); // only the real document id, POISON skipped
        assertThat(outputQueue.poll()).isEqualTo("bc6852541ef5200206a7a9740f3d2d62178a1f53b1aa5417ab426c6ec1f7cbc7");
    }

    @Test(timeout = 20000)
    public void index_task_waits_for_quiet_queue_with_positive_queue_poll() throws Exception {
        Map<String, Object> waitMap = new HashMap<>(map) {{
            put("queuePoll", "1s"); // block up to 1s on an empty queue before concluding drained
        }};
        DocumentQueue<Path> queue = inputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getQueueNameFor(Stage.INDEX), Path.class);
        queue.add(Paths.get(ClassLoader.getSystemResource("docs/doc.txt").getPath()));

        long start = System.currentTimeMillis();
        Long nbDocs = new IndexTask(spewer, inputQueueFactory, new Task<>(IndexTask.class.getName(), User.local(), waitMap), null).call();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(nbDocs).isEqualTo(1);
        assertThat(elapsed).isGreaterThanOrEqualTo(900); // proves it blocked on the empty queue instead of exiting instantly
    }

    @Test(timeout = 20000)
    public void index_task_waits_for_quiet_queue_with_sub_second_queue_poll() throws Exception {
        Map<String, Object> waitMap = new HashMap<>(map) {{
            put("queuePoll", "500ms"); // sub-second poll must still block (rounded up to 1s) on an empty queue
        }};
        DocumentQueue<Path> queue = inputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getQueueNameFor(Stage.INDEX), Path.class);
        queue.add(Paths.get(ClassLoader.getSystemResource("docs/doc.txt").getPath()));

        // construct outside of the timed window: cold-start ES/index/extractor setup can itself take
        // close to a second, which would otherwise mask whether the drain loop actually blocked
        IndexTask indexTask = new IndexTask(spewer, inputQueueFactory, new Task<>(IndexTask.class.getName(), User.local(), waitMap), null);

        long start = System.currentTimeMillis();
        Long nbDocs = indexTask.call();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(nbDocs).isEqualTo(1);
        assertThat(elapsed).isGreaterThanOrEqualTo(900); // proves the sub-second poll was rounded up and blocked, instead of exiting instantly
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

        IndexTask indexTask = new IndexTask(spewer, inputQueueFactory, new Task<>(IndexTask.class.getName(), User.local(), map), callback);
        indexTask.call();
        assertThat(progressValues.size()).isGreaterThan(1);
        assertThat(progressValues.get(0)).isLessThan(progressValues.get(progressValues.size() - 1));
        assertThat(progressValues).contains(0.5);
    }

    @Test
    public void index_task_closes_extractor_after_run() throws Exception {
        CREATED_EXTRACTORS.clear();
        DocumentQueue<Path> inputQueue = inputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getQueueNameFor(Stage.INDEX), Path.class);
        inputQueue.add(Paths.get(ClassLoader.getSystemResource("docs/doc.txt").getPath()));

        IndexTask indexTask = new ClosingProbeIndexTask(spewer, inputQueueFactory, new Task<>(IndexTask.class.getName(), User.local(), map), null);
        indexTask.call();

        assertThat(CREATED_EXTRACTORS.size()).isEqualTo(1);
        verify(CREATED_EXTRACTORS.get(0)).close();
    }
}
