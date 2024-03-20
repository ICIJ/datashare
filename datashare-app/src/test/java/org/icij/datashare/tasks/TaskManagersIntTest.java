package org.icij.datashare.tasks;


import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.com.bus.amqp.AmqpQueue;
import org.icij.datashare.extract.RedisBlockingQueue;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


@RunWith(Parameterized.class)
public class TaskManagersIntTest {
    private static AmqpInterlocutor AMQP;
    private final TaskFactoryForTest factory;
    private final EventWaiter eventWaiter;
    private final CountDownLatch taskRunnerWaiter;
    private final Creator<TaskManager> taskManagerCreator;
    private final Creator<TaskSupplier> taskSupplierCreator;

    private TaskRunnerLoop taskRunner;
    private TaskManager taskManager;

    ExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    @Parameterized.Parameters
    public static Collection<Object[]> taskServices() throws Exception {
        PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of(
                "redisAddress", "redis://redis:6379",
                "redisPoolSize", "3",
                "messageBusAddress", "amqp://admin:admin@rabbitmq"));
        AMQP = new AmqpInterlocutor(propertiesProvider);
        AMQP.createAmqpChannelForPublish(AmqpQueue.TASK);
        AMQP.createAmqpChannelForPublish(AmqpQueue.TASK_RESULT);
        AMQP.createAmqpChannelForPublish(AmqpQueue.EVENT);
        RedisBlockingQueue<TaskView<?>> taskQueue = new RedisBlockingQueue<>(propertiesProvider, "tasks:queue:test");
        EventWaiter amqpWaiter = new EventWaiter(3); // progress, cancel, cancelled
        EventWaiter redisWaiter = new EventWaiter(3); // progress, cancel, cancelled

        return asList(new Object[][]{
            {
                (Creator<TaskManager>) () -> new TaskManagerAmqp(AMQP,
                        new RedissonClientFactory().withOptions(Options.from(new PropertiesProvider().getProperties())).create(),
                        amqpWaiter::countDown),
                (Creator<TaskSupplier>) () -> new TaskSupplierAmqp(AMQP),
                new CountDownLatch(1),
                amqpWaiter
            },
            {
                (Creator<TaskManager>) () -> new TaskManagerRedis(propertiesProvider, "tasks:map:test", taskQueue, redisWaiter::countDown),
                (Creator<TaskSupplier>) () -> new TaskSupplierRedis(propertiesProvider, taskQueue),
                new CountDownLatch(1),
                redisWaiter
            }

        });
    }

    public TaskManagersIntTest(Creator<TaskManager> managerCreator, Creator<TaskSupplier> taskSupplierCreator, CountDownLatch taskRunnerWaiter, EventWaiter eventWaiter) {
        this.factory = mock(TaskFactoryForTest.class);
        this.taskManagerCreator = managerCreator;
        this.taskSupplierCreator = taskSupplierCreator;
        this.eventWaiter = eventWaiter;
        this.taskRunnerWaiter = taskRunnerWaiter;
    }

    @Test(timeout = 10000)
    public void test_stop_running_task() throws Exception {
        eventWaiter.setWaiter(new CountDownLatch(3)); // 1 progress, 1 cancel, 1 cancelled
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
        eventWaiter.setWaiter(new CountDownLatch(5)); // 1 progress, 2 cancel, 2 cancelled

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
    public void setUp() throws Exception {
        this.taskManager = taskManagerCreator.create();
        this.taskRunner = new TaskRunnerLoop(factory, taskSupplierCreator.create(), taskRunnerWaiter, 100);
        executor.submit(taskRunner);
        taskRunnerWaiter.await();
    }

    @After
    public void tearDown() throws Exception {
        taskManager.clear();
        taskManager.close();
        taskRunner.close();
        executor.shutdownNow();
        executor.awaitTermination(1, TimeUnit.SECONDS);
    }

    @AfterClass
    public static void afterClass() throws Exception {
        AMQP.close();
    }

    public interface TaskFactoryForTest extends TaskFactory {
        TestSleepingTask createTestSleepingTask(TaskView<Integer> taskView, BiFunction<String, Double, Void> updateCallback);
    }

    public static class EventWaiter {
        private CountDownLatch waiter;

        public EventWaiter(int nbEvt) {
            this.waiter = new CountDownLatch(nbEvt);
        }

        public void await() throws InterruptedException {
            waiter.await();
        }

        public void countDown() {
            waiter.countDown();
        }

        public void setWaiter(CountDownLatch countDownLatch) {
            waiter = countDownLatch;
        }
    }

    @FunctionalInterface
    public interface Creator<T> {
        T create() throws Exception;
    }
}