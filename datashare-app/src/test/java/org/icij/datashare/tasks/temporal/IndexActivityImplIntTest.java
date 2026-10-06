package org.icij.datashare.tasks.temporal;

import co.elastic.clients.elasticsearch._types.Refresh;
import io.temporal.client.WorkflowOptions;
import java.io.Closeable;
import java.nio.file.Paths;
import java.util.List;
import org.apache.commons.io.FileUtils;
import static org.fest.assertions.Assertions.assertThat;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.temporal.TemporalRule;
import static org.icij.datashare.asynctasks.TaskManagerTemporal.WORKFLOWS_DEFAULT;
import org.icij.datashare.asynctasks.temporal.TemporalWorkerOptions;
import org.icij.datashare.asynctasks.temporal.TemporalWorkers;
import org.icij.datashare.asynctasks.temporal.WorkflowRegistry;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.extract.ScanOptions;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.indexing.elasticsearch.IndexOptions;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class IndexActivityImplIntTest {
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
        registry.registerWorkflow(IndexationWorkflowImpl.class, WORKFLOWS_DEFAULT);
        registry.registerActivity(new ScanActivityImpl(temporal.getClient()), WORKFLOWS_DEFAULT);
        registry.registerActivity(new IndexActivityImpl(propertiesProvider, new ElasticsearchIndexer(es.client,
                                                                                                     propertiesProvider).withRefresh(
                Refresh.True), text -> Language.ENGLISH), WORKFLOWS_DEFAULT);

        try (Closeable ignored = TemporalWorkers.start(temporal.getClient(), registry, List.of(WORKFLOWS_DEFAULT),
                                                       new TemporalWorkerOptions(1))) {
            temporal.getClient().newWorkflowStub(IndexationWorkflow.class,
                                                 WorkflowOptions.newBuilder().setTaskQueue(WORKFLOWS_DEFAULT)
                                                                .setWorkflowId(es.getIndexName()).build())
                    .run(ScanOptions.defaultValues(), tmp.getRoot().toPath(), es.getIndexName(),
                         IndexOptions.fromPropertiesProvider(propertiesProvider));
        }

        assertThat(es.client.count(c -> c.index(es.getIndexName())).count()).isEqualTo(1L);
    }

    @Before
    public void setUp() throws Exception {
        FileUtils.copyDirectory(Paths.get(getClass().getResource("/data").toURI()).toFile(), tmp.getRoot());
    }
}
