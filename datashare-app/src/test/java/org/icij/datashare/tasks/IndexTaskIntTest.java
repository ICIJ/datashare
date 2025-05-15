package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.icij.spewer.FieldNames;
import org.junit.Rule;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.tasks.PipelineTask.STRING_POISON;

public class IndexTaskIntTest {
    @Rule public ElasticsearchRule es = new ElasticsearchRule();

    private final MemoryDocumentCollectionFactory<Path> inputQueueFactory = new MemoryDocumentCollectionFactory<>();
    private final MemoryDocumentCollectionFactory<String> outputQueueFactory = new MemoryDocumentCollectionFactory<>();
    private Map<String, Object> map = new HashMap<>() {{
        put("defaultProject", "test-datashare");
        put("queueName", "test:queue");
    }};
    private final PropertiesProvider propertiesProvider = new PropertiesProvider(map);
    private final ElasticsearchSpewer spewer = new ElasticsearchSpewer(new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True),
            outputQueueFactory, text -> Language.ENGLISH, new FieldNames(), propertiesProvider);

    @Test
    public void index_task_should_enqueue_poison_pill() throws Exception {
        DocumentQueue<Path> queue = inputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getQueueNameFor(Stage.INDEX), Path.class);
        queue.add(Paths.get(ClassLoader.getSystemResource("docs/doc.txt").getPath()));

        Long nbDocs = new IndexTask(spewer, inputQueueFactory, new Task(IndexTask.class.getName(), User.local(), map), null).runTask();

        assertThat(nbDocs).isEqualTo(1);
        DocumentQueue<String> outputQueue = outputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getOutputQueueNameFor(Stage.INDEX), String.class);
        assertThat(outputQueue).hasSize(2);
        assertThat(outputQueue.poll()).isEqualTo("bc6852541ef5200206a7a9740f3d2d62178a1f53b1aa5417ab426c6ec1f7cbc7");
        assertThat(outputQueue.poll()).isEqualTo(STRING_POISON);
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
}
