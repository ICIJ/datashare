package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import java.io.Closeable;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.io.FileUtils;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageActivityOptions;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageProcessor;
import org.icij.datashare.asynctasks.StageRegistry;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.asynctasks.pipeline.PipelineWorkflowImpl;
import org.icij.datashare.asynctasks.temporal.TemporalRule;
import org.icij.datashare.asynctasks.temporal.TemporalWorkerOptions;
import org.icij.datashare.asynctasks.temporal.TemporalWorkers;
import org.icij.datashare.asynctasks.temporal.WorkflowRegistry;
import org.icij.datashare.tasks.temporal.StageActivityImpl;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.fest.assertions.Assertions.assertThat;

public class TemporalPipelineExecutorIntTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    @ClassRule public static ElasticsearchRule es = new ElasticsearchRule();
    @ClassRule public static TemporalRule temporal = new TemporalRule();

    @Test
    public void test_run_indexes_the_documents_of_the_data_dir_and_reports_completion() throws Exception {
        PropertiesProvider propertiesProvider = new PropertiesProvider();
        ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, propertiesProvider);
        String queue = TemporalPipelineExecutor.taskQueue(propertiesProvider);
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.registerWorkflow(PipelineWorkflowImpl.class, queue);
        StageRegistry stages = new StageRegistry().register(new ScanStage()).register(
                new IndexStage(indexer.withRefresh(Refresh.True), text -> Language.ENGLISH));
        registry.registerActivity(new StageActivityImpl(stages, temporal.interlocutor()), queue);

        boolean completed;
        try (Closeable ignored = new TemporalWorkers(temporal.interlocutor()).start(registry, List.of(queue),
                                                                                    new TemporalWorkerOptions(1))) {
            completed = new TemporalPipelineExecutor(temporal.interlocutor(), propertiesProvider, stages).run(
                    List.of(Stage.SCAN, Stage.INDEX),
                    Map.of("defaultProject", es.getIndexName(), "dataDir", tmp.getRoot().getPath()));
        }

        assertThat(completed).isTrue();
        Optional<? extends Entity> doc = indexer.search(List.of(es.getIndexName()), Document.class).execute().findFirst();
        assertThat(doc.isPresent()).isTrue();
        assertThat(((Document) doc.get()).getContent()).isEqualTo("this is the content of downloadDoc");
    }

    @Test
    public void test_run_reports_failure_when_a_stage_exhausts_its_declared_attempts() throws Exception {
        PropertiesProvider propertiesProvider = new PropertiesProvider();
        String queue = TemporalPipelineExecutor.taskQueue(propertiesProvider);
        AtomicInteger calls = new AtomicInteger();
        StageRegistry stages = new StageRegistry().register(new StageProcessor<Void, Void>() {
            public Stage stage() {return Stage.INDEX;}

            public Class<Void> itemType() {return Void.class;}

            public StageResult process(List<Void> batch, StageContext<Void> ctx) {
                calls.incrementAndGet();
                throw new IllegalStateException("boom");
            }

            @Override
            public StageActivityOptions activityOptions() {
                return new StageActivityOptions(Duration.ofMinutes(1), 2);
            }
        });
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.registerWorkflow(PipelineWorkflowImpl.class, queue);
        registry.registerActivity(new StageActivityImpl(stages, temporal.interlocutor()), queue);

        boolean completed;
        try (Closeable ignored = new TemporalWorkers(temporal.interlocutor()).start(registry, List.of(queue),
                                                                                    new TemporalWorkerOptions(1))) {
            completed = new TemporalPipelineExecutor(temporal.interlocutor(), propertiesProvider, stages).run(
                    List.of(Stage.INDEX), Map.of());
        }

        assertThat(completed).isFalse();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Before
    public void setUp() throws Exception {
        FileUtils.copyDirectory(Paths.get(getClass().getResource("/data").toURI()).toFile(), tmp.getRoot());
    }
}
