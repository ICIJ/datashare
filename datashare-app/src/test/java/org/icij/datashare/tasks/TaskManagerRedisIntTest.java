package org.icij.datashare.tasks;


import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.RedisBlockingQueue;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
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
    TaskManagerRedis taskManager = new TaskManagerRedis(propertiesProvider, taskQueue);

    // building a task runner loop with another instance for taskProvider and taskQueue to avoid shared reference tricks
    CountDownLatch latch = new CountDownLatch(1);
    TaskRunnerLoop taskRunner = new TaskRunnerLoop(factory, new TaskSupplierRedis(propertiesProvider, taskQueue), latch, 100);
    ExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    @Before
    public void setUp() throws Exception {
        executor.submit(taskRunner);
        latch.await();
    }

    @Test(timeout = 10000)
    public void test_stop_running_task() throws Exception {
        CountDownLatch taskWaiter = new CountDownLatch(1);
        CountDownLatch eventWaiter = new CountDownLatch(3); // progress, cancel, cancelled
        taskManager.waitForEvents(eventWaiter);
        when(factory.createSleepingTask(any(), any())).thenReturn(new SleepingTask(5000, taskWaiter));
        TaskView<Integer> taskView = taskManager.startTask(SleepingTask.class.getName(), User.local(), new HashMap<>());

        taskWaiter.await();
        taskManager.stopTask(taskView.id);
        eventWaiter.await();

        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTasks().get(0).getState()).isEqualTo(TaskView.State.CANCELLED);
    }

    @Test(timeout = 10000)
    public void test_stop_queued_task() throws Exception {
        CountDownLatch taskWaiter = new CountDownLatch(1);
        CountDownLatch eventWaiter = new CountDownLatch(5); // 1 progress, 2 cancel, 2 cancelled
        taskManager.waitForEvents(eventWaiter);

        when(factory.createSleepingTask(any(), any())).thenReturn(new SleepingTask(5000, taskWaiter));
        TaskView<Integer> tv1 = taskManager.startTask(SleepingTask.class.getName(), User.local(), new HashMap<>());
        TaskView<Integer> tv2 = taskManager.startTask(SleepingTask.class.getName(), User.local(), new HashMap<>());

        taskWaiter.await();
        taskManager.stopTask(tv2.id);
        taskManager.stopTask(tv1.id);
        eventWaiter.await();

        assertThat(taskManager.getTasks()).hasSize(2);
        assertThat(taskManager.getTasks().get(0).getState()).isEqualTo(TaskView.State.CANCELLED);
        assertThat(taskManager.getTasks().get(1).getState()).isEqualTo(TaskView.State.CANCELLED);
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
        TestTask createTestTask(TaskView<Integer> taskView, BiFunction<String, Double, Void> updateCallback);
        SleepingTask createSleepingTask(TaskView<Integer> taskView, BiFunction<String, Double, Void> updateCallback);
    }

    public static class TestTask implements CancellableCallable<Integer> {
        private final int value;
        private final CountDownLatch waiter;
        private Thread callThread;

        public TestTask(int value, CountDownLatch waiter) {
            this.value = value;
            this.waiter = waiter;
        }

        @Override
        public Integer call() throws Exception {
            callThread = Thread.currentThread();
            waiter.countDown();
            return value;
        }

        @Override
        public void cancel(String taskId, boolean requeue) {
            callThread.interrupt();
        }
    }

    public static class SleepingTask extends TestTask {
        public SleepingTask(int value, CountDownLatch waiter) {
            super(value, waiter);
        }

        @Override
        public Integer call() throws Exception {
            int ret = super.call();
            try {
                Thread.sleep(ret);
                return ret;
            } catch (InterruptedException iex) {
                throw new CancelException(null);
            }
        }
    }
}