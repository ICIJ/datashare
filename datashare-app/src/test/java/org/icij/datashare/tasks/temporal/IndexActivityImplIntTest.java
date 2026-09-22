package org.icij.datashare.tasks.temporal;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.TaskFactory;
import org.icij.datashare.asynctasks.TaskManagerTemporal;
import org.icij.datashare.asynctasks.TaskRepository;
import org.icij.datashare.asynctasks.TaskRepositoryRedis;
import org.icij.datashare.asynctasks.temporal.TemporalInterlocutor;
import org.icij.datashare.tasks.RoutingStrategy;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.Mock;
import org.redisson.api.RedissonClient;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.icij.datashare.asynctasks.temporal.TemporalInterlocutor.DEFAULT_NAMESPACE;

public class IndexActivityImplIntTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    static RedissonClient redissonClient = new RedissonClientFactory().withOptions(
            Options.from(new PropertiesProvider(Map.of("redisAddress", "redis://redis:6379")).getProperties())).create();
    // Warning : the order is not guaranteed for redisson map for tasks
    static TaskRepository taskRepository = new TaskRepositoryRedis(redissonClient, "tasks:queue:test");
    @Mock
    private TaskFactory taskFactory;
    private static TemporalInterlocutor temporal;
    private static TaskManagerTemporal taskManager;


    @Test
    public void test_index() {

    }

    // same as TaskManagerTemporal to be refactored in a Test Rule
    @BeforeClass
    public static void setUpClass() throws InterruptedException {
        temporal = new TemporalInterlocutor(EnvUtils.resolve("temporalAddress", "temporal:7233"), DEFAULT_NAMESPACE);
        taskManager = new TaskManagerTemporal(temporal, taskRepository, RoutingStrategy.UNIQUE);
    }

    @Before
    public void setUp() throws IOException, InterruptedException {
        try {
            temporal.deleteNamespace(Duration.ofSeconds(5));
        } catch (StatusRuntimeException ex) {
            if (!ex.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
                throw ex;
            }
        }
        temporal.setupNamespace(Duration.ofSeconds(5));
        taskManager.clear();
        Thread.sleep(2000); // Sleep to allow custom attribute creation propagation refresh rate is 0.1s
    }
}