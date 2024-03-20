package org.icij.datashare.tasks;


import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.com.bus.amqp.AmqpQueue;
import org.icij.datashare.com.bus.amqp.AmqpServerRule;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
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
    @ClassRule static public AmqpServerRule qpid = new AmqpServerRule(5672);
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

        TestSleepingTask sleepingTask = new TestSleepingTask(5000);
        when(factory.createTestSleepingTask(any(), any())).thenReturn(sleepingTask);
        TaskView<Integer> taskView = taskManager.startTask(TestSleepingTask.class.getName(), User.local(), new HashMap<>());

        sleepingTask.awaitToBeStarted();
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

        TestSleepingTask sleepingTask = new TestSleepingTask(5000);
        when(factory.createTestSleepingTask(any(), any())).thenReturn(sleepingTask);
        TaskView<Integer> tv1 = taskManager.startTask(TestSleepingTask.class.getName(), User.local(), new HashMap<>());
        TaskView<Integer> tv2 = taskManager.startTask(TestSleepingTask.class.getName(), User.local(), new HashMap<>());

        sleepingTask.awaitToBeStarted();
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
        TestSleepingTask createTestSleepingTask(TaskView<Integer> taskView, BiFunction<String, Double, Void> updateCallback);
    }

    @BeforeClass
    public static void beforeClass() throws Exception {
        AMQP = new AmqpInterlocutor(new PropertiesProvider(new HashMap<>() {{
            put("messageBusAddress", "amqp://admin:admin@localhost?deadLetter=false");
        }}));
        AMQP.createAmqpChannelForPublish(AmqpQueue.TASK);
        AMQP.createAmqpChannelForPublish(AmqpQueue.TASK_RESULT);
        AMQP.createAmqpChannelForPublish(AmqpQueue.EVENT);
    }

    @AfterClass
    public static void afterClass() throws Exception {
        AMQP.close();
    }
}