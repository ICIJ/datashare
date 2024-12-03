package org.icij.datashare.asynctasks;


import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.asynctasks.bus.amqp.AmqpQueue;
import org.icij.datashare.asynctasks.bus.amqp.AmqpServerRule;
import org.icij.datashare.tasks.RoutingStrategy;
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
import org.redisson.Redisson;
import org.redisson.RedissonMap;
import org.redisson.api.RedissonClient;
import org.redisson.command.CommandSyncService;
import org.redisson.liveobject.core.RedissonObjectBuilder;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.SECONDS;
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

    private TaskWorkerLoop taskWorker;
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
        Map<String, Task<?>> amqpTasks = new RedissonMap<>(new TaskManagerRedis.TaskViewCodec(),
            new CommandSyncService(((Redisson) redissonClient).getConnectionManager(),
                new RedissonObjectBuilder(redissonClient)),
            "tasks:queue:test",
            redissonClient,
            null,
            null
        );
        AMQP = new AmqpInterlocutor(propertiesProvider);
        AMQP.deleteQueues(AmqpQueue.MANAGER_EVENT, AmqpQueue.WORKER_EVENT, AmqpQueue.TASK);
        AMQP.createAmqpChannelForPublish(AmqpQueue.TASK);
        AMQP.createAmqpChannelForPublish(AmqpQueue.WORKER_EVENT);
        AMQP.createAmqpChannelForPublish(AmqpQueue.MANAGER_EVENT);
        EventWaiter amqpWaiter = new EventWaiter(2); // default: progress, result
        EventWaiter redisWaiter = new EventWaiter(2); // default: progress, result

        return asList(new Object[][]{
            {
                (Creator<TaskManager>) () -> new TaskManagerAmqp(AMQP, amqpTasks, RoutingStrategy.UNIQUE, amqpWaiter::countDown),
                (Creator<TaskSupplier>) () -> new TaskSupplierAmqp(AMQP),
                amqpWaiter
            },
            {
                (Creator<TaskManager>) () -> new TaskManagerRedis(redissonClient,
                        "tasks:map:test", RoutingStrategy.UNIQUE, redisWaiter::countDown),
                (Creator<TaskSupplier>) () -> new TaskSupplierRedis(redissonClient),
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
        String taskViewId = taskManager.startTask(TestFactory.SleepForever.class, User.local(), new HashMap<>());

        taskInspector.awaitStatus(taskViewId, Task.State.RUNNING, 1, SECONDS);
        taskManager.stopTask(taskViewId);
        taskInspector.awaitStatus(taskViewId, Task.State.CANCELLED, 1, SECONDS);
        eventWaiter.await();

        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTasks().get(0).getState()).isEqualTo(Task.State.CANCELLED);
    }

    @Test(timeout = 10000)
    public void test_stop_queued_task() throws Exception {
        eventWaiter.setWaiter(new CountDownLatch(3)); // 1 progress, 2 cancelled

        String tv1Id = taskManager.startTask(TestFactory.SleepForever.class, User.local(), new HashMap<>());
        String tv2Id = taskManager.startTask(TestFactory.SleepForever.class, User.local(), new HashMap<>());

        taskInspector.awaitStatus(tv1Id, Task.State.RUNNING, 1, SECONDS);
        taskManager.stopTask(tv2Id);
        taskManager.stopTask(tv1Id);
        taskInspector.awaitStatus(tv1Id, Task.State.CANCELLED, 1, SECONDS);
        taskInspector.awaitStatus(tv2Id, Task.State.CANCELLED, 1, SECONDS);

        assertThat(taskManager.getTasks()).hasSize(2);
        assertThat(taskManager.getTasks().get(0).getState()).isEqualTo(Task.State.CANCELLED);
        assertThat(taskManager.getTasks().get(1).getState()).isEqualTo(Task.State.CANCELLED);
    }

    @Before
    public void setUp() throws Exception {
        this.taskManager = taskManagerCreator.create();
        this.taskInspector = new TaskInspector(this.taskManager);
        CountDownLatch taskRunnerWaiter = new CountDownLatch(1);
        this.taskWorker = new TaskWorkerLoop(factory, taskSupplierCreator.create(), taskRunnerWaiter, 100);
        executor.submit(taskWorker);
        taskRunnerWaiter.await();
    }

    @After
    public void tearDown() throws Exception {
        taskWorker.close();
        taskManager.clear();
        taskManager.close();
        executor.shutdownNow();
        executor.awaitTermination(1, SECONDS);
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