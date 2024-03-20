package org.icij.datashare.tasks;


import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.com.bus.amqp.AmqpQueue;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TaskManagerAmqpIntTest {
    private static AmqpInterlocutor AMQP;
    TaskFactoryForTest factory = mock(TaskFactoryForTest.class);
    TaskManagerAmqp taskManager;
    TaskSupplierAmqp taskSupplier;

    // building a task runner loop with another instance for taskProvider and taskQueue to avoid shared reference tricks
    CountDownLatch latch = new CountDownLatch(1);
    TaskRunnerLoop taskRunner;
    ExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    @Test(timeout = 10000)
    public void test_stop_running_task() throws Exception {
        CountDownLatch eventWaiter = new CountDownLatch(3); // progress, cancel, cancelled
        taskManager = new TaskManagerAmqp(AMQP,
                new RedissonClientFactory().withOptions(Options.from(new PropertiesProvider().getProperties())).create(),
                eventWaiter::countDown);

        CountDownLatch taskWaiter = new CountDownLatch(1);
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
        CountDownLatch eventWaiter = new CountDownLatch(5); // 1 progress, 2 cancel, 2 cancelled
        taskManager = new TaskManagerAmqp(AMQP,
                new RedissonClientFactory().withOptions(Options.from(new PropertiesProvider().getProperties())).create(),
                eventWaiter::countDown);

        CountDownLatch taskWaiter = new CountDownLatch(1);
        when(factory.createSleepingTask(any(), any())).thenReturn(new SleepingTask(5000, taskWaiter));
        TaskView<Integer> tv1 = taskManager.startTask(TaskManagerRedisIntTest.SleepingTask.class.getName(), User.local(), new HashMap<>());
        TaskView<Integer> tv2 = taskManager.startTask(TaskManagerRedisIntTest.SleepingTask.class.getName(), User.local(), new HashMap<>());

        taskWaiter.await();
        taskManager.stopTask(tv2.id);
        taskManager.stopTask(tv1.id);
        eventWaiter.await();

        assertThat(taskManager.getTasks()).hasSize(2);
        assertThat(taskManager.getTasks().get(0).getState()).isEqualTo(TaskView.State.CANCELLED);
        assertThat(taskManager.getTasks().get(1).getState()).isEqualTo(TaskView.State.CANCELLED);
    }

    @Before
    public void setUp() throws IOException {
        taskSupplier = new TaskSupplierAmqp(AMQP);
        taskRunner = new TaskRunnerLoop(factory, taskSupplier, latch, 100);
        executor.submit(taskRunner);
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

    @BeforeClass
    public static void beforeClass() throws Exception {
        AMQP = new AmqpInterlocutor(new PropertiesProvider(new HashMap<>() {{
            put("messageBusAddress", "amqp://admin:admin@rabbitmq");
        }}));
        AMQP.createAmqpChannelForPublish(AmqpQueue.TASK);
        AMQP.createAmqpChannelForPublish(AmqpQueue.TASK_RESULT);
        AMQP.createAmqpChannelForPublish(AmqpQueue.EVENT);
    }

    @AfterClass
    public static void afterClass() throws Exception {
        AMQP.close();
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