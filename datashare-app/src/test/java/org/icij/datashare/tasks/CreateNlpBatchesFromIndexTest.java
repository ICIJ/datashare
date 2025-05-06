package org.icij.datashare.tasks;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch._types.Refresh;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

public class CreateNlpBatchesFromIndexTest {
    @Rule
    public DatashareTimeRule time = new DatashareTimeRule();

    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private static final ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider())
        .withRefresh(Refresh.True);
    private static TaskManager taskManager;

    @Before
    public void setUp() {
        DatashareTaskFactory factory = mock(DatashareTaskFactory.class);
        when(factory.createBatchNlpTask(any(), any())).thenReturn(mock(BatchNlpTask.class));
        taskManager = new TaskManagerMemory(factory, new TaskRepositoryMemory(), new PropertiesProvider());
    }

    @After
    public void tearDown() throws IOException {
        es.removeAll();
        taskManager.close();
    }

    @Test
    public void test_queue_for_batch_nlp_by_batch_with_body() throws Exception {
        // Given
        int batchSize = 3;
        int scrollSize = 5;
        indexer.add(TEST_INDEX, createDoc("my_id").with("this is my precious doc")
            .with(Pipeline.Type.CORENLP).with(project(TEST_INDEX)).build());
        indexer.add(TEST_INDEX, createDoc("my_other_id").with("this is not my precious doc")
            .withExtractionLevel((short) 1)
            .with(Pipeline.Type.CORENLP).with(project(TEST_INDEX)).build());
        Map<String, Object> properties = Map.of(
            "defaultProject", "test-datashare",
            "stages", "BATCHENQUEUEIDX",
            "queueName", "test:queue",
            "nlpPipeline", "OPENNLP",
            "batchSize", batchSize,
            "scrollSize", scrollSize,
            "searchQuery", """
                {
                    "match": {
                      "extractionLevel": 0
                    }
                }
                """
        );
        CreateNlpBatchesFromIndex enqueueFromIndex = new CreateNlpBatchesFromIndex(taskManager, indexer,
            new Task(CreateNlpBatchesFromIndex.class.getName(), new User("test"), properties), null);
        // When
        enqueueFromIndex.call();
        List<List<String>> queued = taskManager.getTasks()
            .map(t -> ((List<CreateNlpBatchesFromIndex.BatchDocument>) t.args.get("docs")).stream().map(
                CreateNlpBatchesFromIndex.BatchDocument::id).toList())
            .toList();
        // Then
        List<List<String>> expected = List.of(List.of("my_id"));
        assertThat(queued).isEqualTo(expected);
    }
}
