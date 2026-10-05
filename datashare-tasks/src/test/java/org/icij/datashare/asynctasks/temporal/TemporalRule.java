package org.icij.datashare.asynctasks.temporal;

import io.temporal.client.WorkflowClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.TaskManagerTemporal;
import org.icij.datashare.asynctasks.TaskRepositoryRedis;
import org.icij.datashare.tasks.RoutingStrategy;
import org.icij.datashare.text.StringUtils;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.junit.rules.ExternalResource;
import org.redisson.api.RedissonClient;


public class TemporalRule extends ExternalResource {
    private TemporalInterlocutor interlocutor;
    private RedissonClient redissonClient;
    private TaskRepositoryRedis taskRepository;
    private TaskManagerTemporal taskManager;

    @Override
    protected void before() throws InterruptedException {
        String namespace = "test-" + StringUtils.generateString(8);
        interlocutor = new TemporalInterlocutor(EnvUtils.resolve("temporalAddress", "temporal:7233"), namespace);
        redissonClient = new RedissonClientFactory().withOptions(Options.from(new PropertiesProvider(
                Map.of("redisAddress", EnvUtils.resolveUri("redis", "redis://redis:6379"))).getProperties())).create();
        taskRepository = new TaskRepositoryRedis(redissonClient, "tasks:queue:" + namespace);
        taskManager = createTaskManager(RoutingStrategy.UNIQUE);
    }

    @Override
    protected void after() {
        try {
            taskManager.close();
            taskRepository.deleteAll();
            interlocutor.deleteNamespace(Duration.ofSeconds(5));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            interlocutor.getClient().getWorkflowServiceStubs().shutdown();
            redissonClient.shutdown();
        }
    }

    public TemporalInterlocutor interlocutor() {
        return interlocutor;
    }

    public TaskRepositoryRedis taskRepository() {
        return taskRepository;
    }

    public TaskManagerTemporal taskManager() {
        return taskManager;
    }

    public TaskManagerTemporal createTaskManager(RoutingStrategy routingStrategy) {
        return new TaskManagerTemporal(interlocutor, taskRepository, routingStrategy);
    }

    public WorkflowClient getClient() {
        return interlocutor.getClient();
    }
}
