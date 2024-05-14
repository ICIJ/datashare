package org.icij.datashare.asynctasks;


import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.asynctasks.bus.amqp.AmqpQueue;
import org.icij.datashare.asynctasks.bus.amqp.AmqpServerRule;
import org.icij.datashare.asynctasks.bus.redis.RedisBlockingQueue;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.ClassRule;
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
import org.redisson.api.RedissonClient;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;


@RunWith(Parameterized.class)
public class TaskManagersIntTest {
    private static AmqpInterlocutor AMQP;
    private final TestFactory factory = new TestFactory();
    @ClassRule
    static public AmqpServerRule qpid = new AmqpServerRule(5672);
    private final EventWaiter eventWaiter;
    private final Creator<TaskManager> taskManagerCreator;
    private final Creator<TaskSupplier> taskSupplierCreator;

    private TaskRunnerLoop taskRunner;
    private TaskManager taskManager;
    private TaskInspector taskInspector;

    ExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    @Parameterized.Parameters
    public static Collection<Object[]> taskServices() throws Exception {
        PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of(
                "redisAddress", "redis://redis:6379",
                "redisPoolSize", "3",
                "messageBusAddress", "amqp://admin:admin@rabbitmq"));
        final RedissonClient redissonClient = new RedissonClientFactory().withOptions(
            Options.from(propertiesProvider.getProperties())).create();
        AMQP = new AmqpInterlocutor(propertiesProvider);
        AMQP.createAmqpChannelForPublish(AmqpQueue.TASK);
        AMQP.createAmqpChannelForPublish(AmqpQueue.TASK_RESULT);
        AMQP.createAmqpChannelForPublish(AmqpQueue.EVENT);
        RedisBlockingQueue<TaskView<?>> taskQueue = new RedisBlockingQueue<>(redissonClient, "tasks:queue:test");
        EventWaiter amqpWaiter = new EventWaiter(2); // default: progress, result
        EventWaiter redisWaiter = new EventWaiter(2); // default: progress, result

        return asList(new Object[][]{
            {
                (Creator<TaskManager>) () -> new TaskManagerAmqp(AMQP, new RedissonClientFactory().withOptions(Options.from(propertiesProvider.getProperties())).create(), "tasks:queue:test", amqpWaiter::countDown),
                (Creator<TaskSupplier>) () -> new TaskSupplierAmqp(AMQP),
                amqpWaiter
            },
            {
                (Creator<TaskManager>) () -> new TaskManagerRedis(redissonClient, taskQueue, "tasks:map:test", redisWaiter::countDown),
                (Creator<TaskSupplier>) () -> new TaskSupplierRedis(redissonClient, taskQueue),
                redisWaiter
            }
        });
    }

    public TaskManagersIntTest(Creator<TaskManager> managerCreator, Creator<TaskSupplier> taskSupplierCreator, EventWaiter eventWaiter) {
        this.taskManagerCreator = managerCreator;
        this.taskSupplierCreator = taskSupplierCreator;
        this.eventWaiter = eventWaiter;
    }

    @Test(timeout = 10000)
    public void test_stop_running_task() throws Exception {
        eventWaiter.setWaiter(new CountDownLatch(2)); // 1 progress, 1 cancelled
        TaskView<Integer> taskView = taskManager.startTask(TestFactory.SleepForever.class.getName(), User.local(), new HashMap<>());

        taskInspector.awaitStatus(taskView.id, TaskView.State.RUNNING);

        taskManager.stopTask(taskView.id);
        taskInspector.awaitStatus(taskView.id, TaskView.State.CANCELLED);
        eventWaiter.await();

        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTasks().get(0).getState()).isEqualTo(TaskView.State.CANCELLED);
    }

    @Test(timeout = 10000)
    public void test_stop_queued_task() throws Exception {
        eventWaiter.setWaiter(new CountDownLatch(3)); // 1 progress, 2 cancelled

        TaskView<Integer> tv1 = taskManager.startTask(TestFactory.SleepForever.class.getName(), User.local(), new HashMap<>());
        TaskView<Integer> tv2 = taskManager.startTask(TestFactory.SleepForever.class.getName(), User.local(), new HashMap<>());

        taskInspector.awaitStatus(tv1.id, TaskView.State.RUNNING);
        taskManager.stopTask(tv2.id);
        taskManager.stopTask(tv1.id);
        taskInspector.awaitStatus(tv1.id, TaskView.State.CANCELLED);
        taskInspector.awaitStatus(tv2.id, TaskView.State.CANCELLED);

        assertThat(taskManager.getTasks()).hasSize(2);
        assertThat(taskManager.getTasks().get(0).getState()).isEqualTo(TaskView.State.CANCELLED);
        assertThat(taskManager.getTasks().get(1).getState()).isEqualTo(TaskView.State.CANCELLED);
    }

    @Before
    public void setUp() throws Exception {
        this.taskManager = taskManagerCreator.create();
        this.taskInspector = new TaskInspector(this.taskManager);
        CountDownLatch taskRunnerWaiter = new CountDownLatch(1);
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