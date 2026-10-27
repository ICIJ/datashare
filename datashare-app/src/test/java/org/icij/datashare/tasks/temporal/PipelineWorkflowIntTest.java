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
import org.icij.datashare.tasks.IndexStageProcessor;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PipelineWorkflowIntTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    @Rule
    public TemporaryFolder outside = new TemporaryFolder();
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    @ClassRule
    public static TemporalRule temporal = new TemporalRule();

    @Test
    public void test_index() throws Exception {
        PropertiesProvider propertiesProvider = new PropertiesProvider();
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.registerWorkflow(PipelineWorkflowImpl.class, WORKFLOWS_DEFAULT);
        registry.registerWorkflow(IndexationWorkflowImpl.class, WORKFLOWS_DEFAULT);
        PropertiesProvider serverProperties = new PropertiesProvider(Map.of("dataDir", tmp.getRoot().toString()));
        registry.registerActivity(new ScanActivityImpl(temporal.interlocutor(), serverProperties), WORKFLOWS_DEFAULT);
        ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, propertiesProvider);
        registry.registerActivity(new StageActivityImpl(new StageRegistry().register(new ScanStage()).register(
                new IndexStageProcessor(indexer.withRefresh(Refresh.True), text -> Language.ENGLISH)),
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

    @Test
    public void test_scan_honors_the_scan_options() throws Exception {
        assertThat(scan(ScanOptions.builder().excludePattern("**").build(), "excluded")).isEqualTo(0L);
    }

    @Test
    public void test_scan_never_follows_a_link_out_of_the_data_dir() throws Exception {
        Files.writeString(outside.getRoot().toPath().resolve("secret.txt"), "outside the data dir");
        Files.createSymbolicLink(tmp.getRoot().toPath().resolve("link"), outside.getRoot().toPath());

        assertThat(scan(ScanOptions.builder().followSymlinks(true).build(), "links")).isEqualTo(1L);
    }

    private Long scan(ScanOptions scanOptions, String workflowIdSuffix) throws Exception {
        PropertiesProvider propertiesProvider = new PropertiesProvider();
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.registerWorkflow(IndexationWorkflowImpl.class, WORKFLOWS_DEFAULT);
        PropertiesProvider serverProperties = new PropertiesProvider(Map.of("dataDir", tmp.getRoot().toString()));
        registry.registerActivity(new ScanActivityImpl(temporal.interlocutor(), serverProperties), WORKFLOWS_DEFAULT);
        registry.registerActivity(new IndexActivityImpl(
                new ElasticsearchIndexer(es.client, propertiesProvider), text -> Language.ENGLISH), WORKFLOWS_DEFAULT);

        try (Closeable ignored = new TemporalWorkers(temporal.interlocutor()).start(registry, List.of(WORKFLOWS_DEFAULT),
                                                       new TemporalWorkerOptions(1))) {
            return temporal.interlocutor().newWorkflowStub(IndexationWorkflow.class,
                                                 WorkflowOptions.newBuilder().setTaskQueue(WORKFLOWS_DEFAULT)
                                                                .setWorkflowId(es.getIndexName() + "-" + workflowIdSuffix)
                                                                .build())
                    .run(scanOptions, tmp.getRoot().toPath(), es.getIndexName(),
                         IndexOptions.fromPropertiesProvider(propertiesProvider),
                         propertiesProvider.toRecord(ArtifactOptions.class));
        }
    }

    @Before
    public void setUp() throws Exception {
        FileUtils.copyDirectory(Paths.get(getClass().getResource("/data").toURI()).toFile(), tmp.getRoot());
    }
}
