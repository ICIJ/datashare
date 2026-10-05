package org.icij.datashare.tasks.temporal;

import co.elastic.clients.elasticsearch._types.Refresh;
import io.temporal.client.WorkflowOptions;
import java.io.Closeable;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import static org.fest.assertions.Assertions.assertThat;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.TaskManagerTemporal;
import org.icij.datashare.asynctasks.TaskRepository;
import org.icij.datashare.asynctasks.TaskRepositoryRedis;
import org.icij.datashare.asynctasks.temporal.TemporalInterlocutor;
import static org.icij.datashare.asynctasks.TaskManagerTemporal.WORKFLOWS_DEFAULT;
import org.icij.datashare.asynctasks.temporal.TemporalWorkerOptions;
import org.icij.datashare.asynctasks.temporal.TemporalWorkers;
import org.icij.datashare.asynctasks.temporal.WorkflowRegistry;
import org.icij.datashare.tasks.RoutingStrategy;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.extract.ScanOptions;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.StringUtils;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.indexing.elasticsearch.IndexOptions;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.redisson.api.RedissonClient;

public class IndexActivityImplIntTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    static RedissonClient redissonClient = new RedissonClientFactory().withOptions(
                                                                              Options.from(new PropertiesProvider(Map.of("redisAddress", "redis://redis:6379")).getProperties()))
                                                                      .create();
    // Warning : the order is not guaranteed for redisson map for tasks
    static TaskRepository taskRepository = new TaskRepositoryRedis(redissonClient, "tasks:queue:test");
    private static TemporalInterlocutor temporal;
    private static TaskManagerTemporal taskManager;

    @Test
    public void test_index() throws Exception {
        PropertiesProvider propertiesProvider = new PropertiesProvider();
        IndexActivityImpl indexActivity = new IndexActivityImpl(propertiesProvider, new ElasticsearchIndexer(es.client,
                                                                                                             propertiesProvider).withRefresh(
                Refresh.True), text -> Language.ENGLISH);
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.registerWorkflow(IndexationWorkflowImpl.class, WORKFLOWS_DEFAULT);
        registry.registerActivity(new ScanActivityImpl(temporal.getClient()), WORKFLOWS_DEFAULT);
        registry.registerActivity(indexActivity, WORKFLOWS_DEFAULT);

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

    // same as TaskManagerTemporalIntTest to be refactored in a Test Rule
    @Before
    public void setUp() throws Exception {
        temporal = new TemporalInterlocutor(EnvUtils.resolve("temporalAddress", "temporal:7233"),
                                            "test-" + StringUtils.generateString(8));
        taskManager = new TaskManagerTemporal(temporal, taskRepository, RoutingStrategy.UNIQUE);
        taskManager.clear();
        FileUtils.copyDirectory(Paths.get(getClass().getResource("/data").toURI()).toFile(), tmp.getRoot());
    }

    @After
    public void tearDown() {
        temporal.deleteNamespace(Duration.ofSeconds(5));
        temporal.getClient().getWorkflowServiceStubs().shutdown();
    }
}
