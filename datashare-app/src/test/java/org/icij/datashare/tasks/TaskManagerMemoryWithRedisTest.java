package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskManagerMemory;
import org.icij.datashare.asynctasks.TaskRepositoryRedis;
import org.icij.datashare.asynctasks.TaskResult;
import org.icij.datashare.db.JooqTaskRepository;
import org.icij.datashare.db.RepositoryFactoryImpl;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.jooq.SQLDialect;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.redisson.api.RedissonClient;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.Assertions.assertThat;

public class TaskManagerMemoryWithRedisTest {
    @Mock DatashareTaskFactory factory;

    private org.icij.datashare.asynctasks.TaskManagerMemory taskManager;
    private final CountDownLatch waitForLoop = new CountDownLatch(1);

    @Before
    public void setUp() throws Exception {
        PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of(
                "redisAddress", "redis://redis:6379",
                "redisPoolSize", "2"
        ));
        final RedissonClient redissonClient = new RedissonClientFactory().withOptions(
                Options.from(propertiesProvider.getProperties())).create();
        taskManager = new TaskManagerMemory(factory, new TaskRepositoryRedis(redissonClient, "test:tasks"), new PropertiesProvider(), waitForLoop);
        waitForLoop.await();
    }

    @Test
    public void test_run_task() throws Exception {
        String tid = taskManager.startTask(TestTask.class, User.local(), new HashMap<>());
        taskManager.waitTasksToBeDone(1, SECONDS);

        assertThat(taskManager.getTask(tid).getState()).isEqualTo(Task.State.ERROR);
        assertThat(taskManager.getTask(tid).getError().getMessage()).isEqualTo("Cannot invoke \"Object.getClass()\" because \"factory\" is null");
    }

    @After
    public void tearDown() throws Exception {
        taskManager.close();
    }

}
