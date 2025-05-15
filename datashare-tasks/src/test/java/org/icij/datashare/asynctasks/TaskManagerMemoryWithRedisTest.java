package org.icij.datashare.asynctasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.redisson.api.RedissonClient;

import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.Assertions.assertThat;

public class TaskManagerMemoryWithRedisTest {
    TaskFactory factory = new TestFactory();

    private TaskManagerMemory taskManager;
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
        String tid = taskManager.startTask(TestFactory.Sleep.class, User.local(), Map.of("duration", 10));

        taskManager.waitTasksToBeDone(1, SECONDS);

        Task task = taskManager.getTask(tid);
        assertThat(task.getState()).isEqualTo(Task.State.DONE);
        assertThat(task.getResult()).isEqualTo("10".getBytes());
    }

    @After
    public void tearDown() throws Exception {
        taskManager.clear();
        taskManager.close();
    }
}
