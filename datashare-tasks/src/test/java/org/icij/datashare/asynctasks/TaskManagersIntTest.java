package org.icij.datashare.asynctasks;


import java.util.List;
import java.util.stream.Stream;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.asynctasks.bus.amqp.AmqpQueue;
import org.icij.datashare.asynctasks.bus.amqp.AmqpServerRule;
import org.icij.datashare.tasks.RoutingStrategy;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.redisson.api.RedissonClient;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

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

        AmqpQueue[] queues = {AmqpQueue.MANAGER_EVENT, AmqpQueue.WORKER_EVENT, AmqpQueue.TASK};
        AMQP = new AmqpInterlocutor(propertiesProvider, queues);
        AMQP.deleteQueues(queues);
        EventWaiter amqpWaiter = new EventWaiter(2); // default: progress, result
        EventWaiter redisWaiter = new EventWaiter(2); // default: progress, result

        return asList(new Object[][]{
            {
                (Creator<TaskManager>) () -> new TaskManagerAmqp(AMQP, new TaskRepositoryRedis(redissonClient), RoutingStrategy.UNIQUE, amqpWaiter::countDown),
                (Creator<TaskSupplier>) () -> new TaskSupplierAmqp(AMQP),
                amqpWaiter
            },
            {
                (Creator<TaskManager>) () -> new TaskManagerRedis(redissonClient,
                        new TaskRepositoryRedis(redissonClient, "tasks:map:test"), RoutingStrategy.UNIQUE, redisWaiter::countDown),
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

        List<Task> tasks = taskManager.getTasks().toList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getState()).isEqualTo(Task.State.CANCELLED);
    }

    @Test(timeout = 10000)
    public void test_stop_queued_task() throws Exception {
        String tv1Id = taskManager.startTask(TestFactory.SleepForever.class, User.local(), new HashMap<>());
        String tv2Id = taskManager.startTask(TestFactory.SleepForever.class, User.local(), new HashMap<>());

        taskInspector.awaitStatus(tv1Id, Task.State.RUNNING, 1, SECONDS);
        taskManager.stopTask(tv2Id);
        taskManager.stopTask(tv1Id);
        taskInspector.awaitStatus(tv1Id, Task.State.CANCELLED, 1, SECONDS);
        taskInspector.awaitStatus(tv2Id, Task.State.CANCELLED, 1, SECONDS);

        assertThat(taskManager.getTasks().toList()).hasSize(2);
        assertThat(taskManager.getTask(tv1Id).getState()).isEqualTo(Task.State.CANCELLED);
        assertThat(taskManager.getTask(tv2Id).getState()).isEqualTo(Task.State.CANCELLED);
    }

    @Test(timeout = 10000)
    public void test_stop_all_tasks() throws Exception {
        String tv1Id = taskManager.startTask(TestFactory.SleepForever.class, User.local(), new HashMap<>());
        String tv2Id = taskManager.startTask(TestFactory.SleepForever.class, User.local(), new HashMap<>());

        taskInspector.awaitStatus(tv1Id, Task.State.RUNNING, 1, SECONDS);
        taskManager.stopTasks(User.local());
        taskInspector.awaitStatus(tv1Id, Task.State.CANCELLED, 1, SECONDS);
        taskInspector.awaitStatus(tv2Id, Task.State.CANCELLED, 1, SECONDS);

        assertThat(taskManager.getTasks().toList()).hasSize(2);
        assertThat(taskManager.getTask(tv1Id).getState()).isEqualTo(Task.State.CANCELLED);
        assertThat(taskManager.getTask(tv2Id).getState()).isEqualTo(Task.State.CANCELLED);
    }

    @Test(timeout = 10000)
    public void test_stop_all_tasks_with_filter() throws Exception {
        String tv1Id = taskManager.startTask(TestFactory.AnotherSleepForever.class, User.local(), new HashMap<>());
        String tv2Id = taskManager.startTask(TestFactory.SleepForever.class, User.local(), new HashMap<>());

        taskInspector.awaitStatus(tv1Id, Task.State.RUNNING, 1, SECONDS);
        taskManager.stopTasks(TaskFilters.empty().withUser(User.local()).withNames(".*Another.*"));
        taskInspector.awaitStatus(tv1Id, Task.State.CANCELLED, 1, SECONDS);

        assertThat(taskManager.getTasks().toList()).hasSize(2);
        assertThat(taskManager.getTask(tv1Id).getState()).isEqualTo(Task.State.CANCELLED);
        assertThat(taskManager.getTask(tv2Id).getState()).isEqualTo(Task.State.RUNNING);

        taskManager.stopTasks(User.local());
        taskInspector.awaitStatus(tv2Id, Task.State.CANCELLED, 1, SECONDS);
    }


    @Test(timeout = 10000)
    public void test_stop_all_wait_clear_done_tasks() throws Exception {
        String tv1Id = taskManager.startTask(TestFactory.SleepForever.class, User.local(), new HashMap<>());
        String tv2Id = taskManager.startTask(TestFactory.SleepForever.class, User.local(), new HashMap<>());

        taskInspector.awaitStatus(tv1Id, Task.State.RUNNING, 1, SECONDS);
        taskManager.stopTasks(User.local());
        taskInspector.awaitStatus(tv1Id, Task.State.CANCELLED, 1, SECONDS);

        assertThat(taskManager.getTask(tv1Id).getState()).isEqualTo(Task.State.CANCELLED);
        assertThat(taskManager.getTask(tv2Id).getState()).isEqualTo(Task.State.CANCELLED);

        taskManager.clearDoneTasks();

        assertThat(taskManager.getTasks().toList()).isEmpty();
    }

    @Test(timeout = 10000)
    public void test_stop_all_wait_clear_done_tasks_not_cancellable_task() throws Exception {
        String tv1Id = taskManager.startTask(TestFactory.Sleep.class, User.local(), Map.of("duration", 3000));
        String tv2Id = taskManager.startTask(TestFactory.SleepForever.class, User.local(), new HashMap<>());

        taskInspector.awaitStatus(tv1Id, Task.State.RUNNING, 1, SECONDS);
        taskManager.stopTasks(User.local());

        assertThat(taskManager.getTask(tv1Id).getState()).isEqualTo(Task.State.RUNNING);
        assertThat(taskManager.getTask(tv2Id).getState()).isEqualTo(Task.State.CREATED);

        taskManager.clearDoneTasks();

        assertThat(taskManager.getTasks().toList()).isNotEmpty();
    }

    @Test(timeout = 10000)
    public void test_await_tasks_termination() throws Exception {
        String tv1Id = taskManager.startTask(TestFactory.Sleep.class, User.local(), Map.of("duration", 100));
        String tv2Id = taskManager.startTask(TestFactory.Sleep.class, User.local(), Map.of("duration", 200));

        taskManager.awaitTermination(2, SECONDS);
        assertThat(taskManager.getTask(tv1Id).getState()).isEqualTo(Task.State.DONE);
        assertThat(taskManager.getTask(tv2Id).getState()).isEqualTo(Task.State.DONE);
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