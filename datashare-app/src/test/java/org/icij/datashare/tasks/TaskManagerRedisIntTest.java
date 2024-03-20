package org.icij.datashare.tasks;


import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.RedisBlockingQueue;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TaskManagerRedisIntTest {
    TaskFactoryForTest factory = mock(TaskFactoryForTest.class);
    PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of("redisAddress", "redis://redis:6379", "redisPoolSize", "3"));
    RedisBlockingQueue<TaskView<?>> taskQueue = new RedisBlockingQueue<>(propertiesProvider, "tasks:queue:test");
    TaskManagerRedis taskManager = new TaskManagerRedis(propertiesProvider, "tasks:map:test", taskQueue, this::eventCallback);

    // building a task runner loop with another instance for taskProvider and taskQueue to avoid shared reference tricks
    CountDownLatch latch = new CountDownLatch(1);
    CountDownLatch eventWaiter = new CountDownLatch(1);
    TaskRunnerLoop taskRunner = new TaskRunnerLoop(factory, new TaskSupplierRedis(propertiesProvider, taskQueue), latch, 100);
    ExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    @Before
    public void setUp() throws Exception {
        executor.submit(taskRunner);
        latch.await();
    }

    @Test(timeout = 10000)
    public void test_stop_running_task() throws Exception {
        eventWaiter = new CountDownLatch(3); // progress, cancel, cancelled
        TestSleepingTask t = new TestSleepingTask(5000);
        when(factory.createTestSleepingTask(any(), any())).thenReturn(t);
        TaskView<Integer> taskView = taskManager.startTask(TestSleepingTask.class.getName(), User.local(), new HashMap<>());

        t.awaitToBeStarted();
        taskManager.stopTask(taskView.id);
        eventWaiter.await();

        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTasks().get(0).getState()).isEqualTo(TaskView.State.CANCELLED);
    }

    @Test(timeout = 10000)
    public void test_stop_queued_task() throws Exception {
        eventWaiter = new CountDownLatch(5); // 1 progress, 2 cancel, 2 cancelled

        TestSleepingTask t1 = new TestSleepingTask(5000);
        TestSleepingTask t2 = new TestSleepingTask(5000);
        when(factory.createTestSleepingTask(any(), any())).thenReturn(t1, t2);
        TaskView<Integer> tv1 = taskManager.startTask(TestSleepingTask.class.getName(), User.local(), new HashMap<>());
        TaskView<Integer> tv2 = taskManager.startTask(TestSleepingTask.class.getName(), User.local(), new HashMap<>());

        t1.awaitToBeStarted();
        taskManager.stopTask(tv2.id);
        taskManager.stopTask(tv1.id);
        eventWaiter.await();

        assertThat(taskManager.getTasks()).hasSize(2);
        assertThat(taskManager.getTasks().get(0).getState()).isEqualTo(TaskView.State.CANCELLED);
        assertThat(taskManager.getTasks().get(1).getState()).isEqualTo(TaskView.State.CANCELLED);
    }

    private void eventCallback() {
        eventWaiter.countDown();
    }

    @After
    public void tearDown() throws Exception {
        taskManager.clear();
        taskManager.close();
        taskRunner.close();
        executor.shutdownNow();
        executor.awaitTermination(1, TimeUnit.SECONDS);
    }

    public interface TaskFactoryForTest extends TaskFactory {
        TestSleepingTask createTestSleepingTask(TaskView<Integer> taskView, BiFunction<String, Double, Void> updateCallback);
    }
}