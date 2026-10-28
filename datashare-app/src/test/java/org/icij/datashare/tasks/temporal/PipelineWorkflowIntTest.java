package org.icij.datashare.tasks.temporal;

import co.elastic.clients.elasticsearch._types.Refresh;
import io.temporal.client.WorkflowOptions;
import java.io.Closeable;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.io.FileUtils;
import static org.fest.assertions.Assertions.assertThat;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import static org.icij.datashare.asynctasks.TaskManagerTemporal.WORKFLOWS_DEFAULT;
import org.icij.datashare.asynctasks.pipeline.PipelineWorkflow;
import org.icij.datashare.asynctasks.pipeline.PipelineWorkflowImpl;
import org.icij.datashare.asynctasks.temporal.TemporalRule;
import org.icij.datashare.asynctasks.temporal.TemporalWorkerOptions;
import org.icij.datashare.asynctasks.temporal.TemporalWorkers;
import org.icij.datashare.asynctasks.temporal.WorkflowRegistry;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageRegistry;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.tasks.ScanStage;
import org.icij.datashare.tasks.IndexStage;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PipelineWorkflowIntTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    @ClassRule
    public static TemporalRule temporal = new TemporalRule();

    @Test
    public void test_index() throws Exception {
        PropertiesProvider propertiesProvider = new PropertiesProvider();
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.registerWorkflow(PipelineWorkflowImpl.class, WORKFLOWS_DEFAULT);
        ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, propertiesProvider);
        registry.registerActivity(new StageActivityImpl(new StageRegistry().register(new ScanStage()).register(
                new IndexStage(indexer.withRefresh(Refresh.True), text -> Language.ENGLISH)),
                temporal.interlocutor()), WORKFLOWS_DEFAULT);

        Map<Stage, StageResult> results;
        try (Closeable ignored = new TemporalWorkers(temporal.interlocutor()).start(registry, List.of(WORKFLOWS_DEFAULT),
                                                       new TemporalWorkerOptions(1))) {
            results = temporal.interlocutor().newWorkflowStub(PipelineWorkflow.class,
                                                 WorkflowOptions.newBuilder().setTaskQueue(WORKFLOWS_DEFAULT)
                                                                .setWorkflowId(es.getIndexName()).build())
                    .run(List.of(Stage.SCAN, Stage.INDEX),
                         Map.of("defaultProject", es.getIndexName(), "dataDir", tmp.getRoot().getPath()));
        }

        assertThat(results.get(Stage.SCAN).updated()).isGreaterThan(0);
        assertThat(results.get(Stage.INDEX).updated()).isEqualTo(results.get(Stage.SCAN).updated());
        assertThat(results.get(Stage.INDEX).failed()).isEqualTo(0);
        Optional<? extends Entity> doc = indexer.search(List.of(es.getIndexName()), Document.class).execute().findFirst();
        assertThat(doc.isPresent()).isTrue();
        assertThat(((Document)doc.get()).getContent()).isEqualTo("this is the content of downloadDoc");
    }

    @Before
    public void setUp() throws Exception {
        FileUtils.copyDirectory(Paths.get(getClass().getResource("/data").toURI()).toFile(), tmp.getRoot());
    }
}
