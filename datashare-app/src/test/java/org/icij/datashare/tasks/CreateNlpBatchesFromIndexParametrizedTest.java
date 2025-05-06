package org.icij.datashare.tasks;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch._types.Refresh;
import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class CreateNlpBatchesFromIndexParametrizedTest {
    private final int batchSize;
    private final int scrollSize;
    private final List<List<Language>> expectedLanguages;

    @Rule
    public DatashareTimeRule time = new DatashareTimeRule();

    static class TestableCreateNlpBatchesFromIndex extends CreateNlpBatchesFromIndex {
        public TestableCreateNlpBatchesFromIndex(
                TaskManager taskManager, Indexer indexer, Task taskView, Function<Double, Void> ignored) {
            super(taskManager, indexer, taskView, ignored);
        }

        protected String enqueueBatch(List<Document> batch) throws IOException {
            DatashareTime.getInstance().addMilliseconds(1);
            return super.enqueueBatch(batch);
        }
    }

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


    public CreateNlpBatchesFromIndexParametrizedTest(int batchSize, int scrollSize,
                                                     List<List<Language>> expectedLanguages) {
        this.batchSize = batchSize;
        this.scrollSize = scrollSize;
        this.expectedLanguages = expectedLanguages;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> taskParams() {
        return List.of(
            new Object[] {7, 3, List.of(
                List.of(Language.ENGLISH, Language.ENGLISH, Language.ENGLISH, Language.ENGLISH, Language.ENGLISH,
                    Language.ENGLISH, Language.ENGLISH),
                List.of(Language.ENGLISH, Language.ENGLISH, Language.ENGLISH),
                List.of(Language.FRENCH, Language.FRENCH, Language.FRENCH, Language.FRENCH, Language.FRENCH),
                List.of(Language.SPANISH, Language.SPANISH, Language.SPANISH, Language.SPANISH, Language.SPANISH)
            )},
            new Object[] {3, 7, List.of(
                List.of(Language.ENGLISH, Language.ENGLISH, Language.ENGLISH),
                List.of(Language.ENGLISH, Language.ENGLISH, Language.ENGLISH),
                List.of(Language.ENGLISH, Language.ENGLISH, Language.ENGLISH),
                List.of(Language.ENGLISH),
                List.of(Language.FRENCH, Language.FRENCH, Language.FRENCH),
                List.of(Language.FRENCH, Language.FRENCH),
                List.of(Language.SPANISH, Language.SPANISH, Language.SPANISH),
                List.of(Language.SPANISH, Language.SPANISH)
            )}
        );
    }

    @Test
    public void test_queue_for_batch_nlp_by_batch() throws Exception {
        // Given
        int numDocs = 20;
        for (int i = 0; i < numDocs; i++) {
            Language language = switch (i % 4) {
                case 2 -> Language.FRENCH;
                case 3 -> Language.SPANISH;
                default -> Language.ENGLISH;
            };
            indexer.add(TEST_INDEX, createDoc("doc" + i).with(language).with(Pipeline.Type.OPENNLP).build());
        }
        // Already processed
        indexer.add(TEST_INDEX,
            createDoc("docAlreadyProcessed").with(Language.ITALIAN).with(Pipeline.Type.CORENLP).build());
        indexer.add(TEST_INDEX,
            createDoc("docAlsoAlreadyProcessed").with(Language.ITALIAN).with(Pipeline.Type.CORENLP).build());
        Map<String, Object> properties = Map.of(
            "defaultProject", "test-datashare",
            "stages", "BATCHENQUEUEIDX",
            "queueName", "test:queue",
            "nlpPipeline", "CORENLP",
            "batchSize", this.batchSize,
            "scrollSize", this.scrollSize
        );
        TestableCreateNlpBatchesFromIndex enqueueFromIndex = new TestableCreateNlpBatchesFromIndex(taskManager, indexer,
                new Task(CreateNlpBatchesFromIndex.class.getName(), new User("test"), properties), null);
        // When
        List<String> taskIds = enqueueFromIndex.runTask();
        List<List<Language>> queued = taskManager.getTasks()
            .sorted(Comparator.comparing(t -> t.createdAt))
            .map(t -> ((List<CreateNlpBatchesFromIndex.BatchDocument>) t.args.get("docs")).stream().map(
                CreateNlpBatchesFromIndex.BatchDocument::language).toList())
            .toList();
        // Then
        assertThat(queued).isEqualTo(this.expectedLanguages);
        assertThat(taskIds.size()).isEqualTo(expectedLanguages.size());
    }
}
