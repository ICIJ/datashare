package org.icij.datashare.asynctasks;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.asynctasks.bus.amqp.AmqpQueue;
import org.icij.datashare.asynctasks.bus.amqp.AmqpServerRule;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.tasks.RoutingStrategy;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.redisson.api.RedissonClient;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;
import static org.junit.Assert.assertThrows;

public class TaskManagerAmqpTest {
    private static AmqpInterlocutor AMQP;
    @ClassRule static public AmqpServerRule qpid = new AmqpServerRule(5672);
    BlockingQueue<Task> taskQueue = new LinkedBlockingQueue<>();
    TaskManagerAmqp taskManager;
    TaskSupplierAmqp taskSupplier;
    CountDownLatch nextMessage;

    @Test(timeout = 2000)
    public void test_new_task() throws Exception {
        String expectedTaskViewId = taskManager.startTask("taskName", User.local(), Map.of("key", "value"));

        assertThat(taskManager.getTask(expectedTaskViewId)).isNotNull();
        Task actualTaskView = taskQueue.poll(1, TimeUnit.SECONDS);
        assertThat(actualTaskView).isNotNull();
        assertThat(actualTaskView.id).isEqualTo(expectedTaskViewId);
    }

    @Test(timeout = 2000)
    public void test_new_task_with_group_routing() throws Exception {
        TaskGroupType key = TaskGroupType.Test;
        try (TaskManagerAmqp groupTaskManager = new TaskManagerAmqp(AMQP, new TaskRepositoryMemory(), RoutingStrategy.GROUP, () -> nextMessage.countDown());
             TaskSupplierAmqp groupTaskSupplier = new TaskSupplierAmqp(AMQP, key.name())) {
            groupTaskSupplier.consumeTasks(t -> taskQueue.add(t));
            String expectedTaskViewId = groupTaskManager.startTask("taskName", User.local(), new Group(key), Map.of());

            assertThat(groupTaskManager.getTask(expectedTaskViewId)).isNotNull();
            Task actualTaskView = taskQueue.poll(1, TimeUnit.SECONDS);
            assertThat(actualTaskView).isNotNull();
            assertThat(groupTaskManager.getTaskGroup(expectedTaskViewId)).isEqualTo(new Group(key));
        }
    }

    @Test(timeout = 2000)
    public void test_new_task_with_name_routing() throws Exception {
        try (TaskManagerAmqp groupTaskManager = new TaskManagerAmqp(AMQP, new TaskRepositoryMemory(), RoutingStrategy.NAME, () -> nextMessage.countDown());
             TaskSupplierAmqp groupTaskSupplier = new TaskSupplierAmqp(AMQP, "TaskName")) {
            groupTaskSupplier.consumeTasks(t -> taskQueue.add(t));
            String expectedTaskViewId = groupTaskManager.startTask("TaskName", User.local(), Map.of());

            assertThat(groupTaskManager.getTask(expectedTaskViewId)).isNotNull();
            Task actualTaskView = taskQueue.poll(1, TimeUnit.SECONDS);
            assertThat(actualTaskView).isNotNull();
            assertThat(actualTaskView.name).isEqualTo("TaskName");
        }
    }

    @Test(timeout = 2000)
    public void test_new_task_two_workers() throws Exception {
        try (TaskSupplierAmqp otherConsumer = new TaskSupplierAmqp(AMQP)) {
            otherConsumer.consumeTasks(t -> taskQueue.add(t));
            taskManager.startTask("taskName1", User.local(), new HashMap<>());
            taskManager.startTask("taskName2", User.local(), new HashMap<>());

            Task actualTask1 = taskQueue.poll(1, TimeUnit.SECONDS);
            Task actualTask2 = taskQueue.poll(1, TimeUnit.SECONDS);

            assertThat(actualTask1).isNotNull();
            assertThat(actualTask2).isNotNull();
        }
    }

    @Test(timeout = 2000)
    public void test_task_progress() throws Exception {
        taskManager.startTask("taskName", User.local(), new HashMap<>());

        // in the task runner loop
        Task task = taskQueue.poll(2, TimeUnit.SECONDS); // to sync
        taskSupplier.progress(task.id,0.5);

        nextMessage.await();
        assertThat(taskManager.getTask(task.id).getProgress()).isEqualTo(0.5);
    }

    @Test(timeout = 2000)
    public void test_task_result() throws Exception {
        taskManager.startTask("taskName", User.local(), new HashMap<>());

        // in the task runner loop
        Task task = taskQueue.poll(2, TimeUnit.SECONDS); // to sync
        byte[] encodedResult = MAPPER.writeValueAsBytes("result");
        taskSupplier.result(task.id, encodedResult);

        nextMessage.await();
        Task storedTask = taskManager.getTask(task.id);
        assertThat(storedTask.getState()).isEqualTo(Task.State.DONE);
        assertThat(storedTask.getResult()).isEqualTo(encodedResult);
    }

    @Test(timeout = 2000)
    public void test_task_error() throws Exception {
        taskManager.startTask("taskName", User.local(), new HashMap<>());

        // in the task runner loop
        Task task = taskQueue.poll(2, TimeUnit.SECONDS); // to sync
        taskSupplier.error(task.id,new TaskError(new RuntimeException("error in runner")));

        nextMessage.await();
        Task storedTask = taskManager.getTask(task.id);
        assertThat(storedTask.getResult()).isNull();
        assertThat(storedTask.getState()).isEqualTo(Task.State.ERROR);
        assertThat(storedTask.error.getMessage()).isEqualTo("error in runner");
    }

    @Test
    public void test_clear_task_among_two_tasks() throws Exception {
        String taskView1Id = taskManager.startTask("taskName1", User.local(), new HashMap<>());
        taskManager.startTask("taskName2", User.local(), new HashMap<>());

        assertThat(taskManager.getTasks().toList()).hasSize(2);

        Task clearedTask = taskManager.clearTask(taskView1Id);

        assertThat(taskView1Id).isEqualTo(clearedTask.id);
        assertThrows(UnknownTask.class, () -> taskManager.getTask(taskView1Id));
        assertThat(taskManager.getTasks().toList()).hasSize(1);
    }

    @Test(expected = IllegalStateException.class)
    public void test_clear_running_task_should_throw_exception() throws Exception {
        taskManager.startTask("taskName", User.local(), new HashMap<>());

        assertThat(taskManager.getTasks().toList()).hasSize(1);

        // in the task runner loop
        Task task = taskQueue.poll(1, TimeUnit.SECONDS); // to sync
        taskSupplier.progress(task.id,0.5);
        nextMessage.await();

        assertThat(taskManager.getTask(task.id).getState()).isEqualTo(Task.State.RUNNING);

        taskManager.clearTask(task.id);
    }

    @Test(timeout = 2000)
    public void test_task_canceled() throws Exception {
        taskManager.startTask("taskName", User.local(), new HashMap<>());

        // in the task runner loop
        Task task = taskQueue.poll(2, TimeUnit.SECONDS); // to sync
        taskSupplier.canceled(task,false);

        nextMessage.await();
        assertThat(taskManager.getTask(task.id).getProgress()).isEqualTo(0.0);
        assertThat(taskManager.getTask(task.id).getState()).isEqualTo(Task.State.CANCELLED);
    }

    @Test
    public void test_save_task() throws IOException {
        Task task = new Task("name", User.local(), new HashMap<>());

        taskManager.insert(task, null);

        assertThat(taskManager.getTasks().toList()).hasSize(1);
        assertThat(taskManager.getTask(task.id)).isNotNull();
    }

    @Test
    public void test_update_task() throws IOException {
        // Given
        Task task = new Task("HelloWorld", User.local(), Map.of("greeted", "world"));
        Task update = new Task(task.id, task.name, task.getState(), 0.5, DatashareTime.getNow(),  3,  null, task.args, null, null);
        // When
        taskManager.insert(task, null);
        taskManager.update(update);
        Task updated = taskManager.getTask(task.id);
        // Then
        assertThat(updated).isEqualTo(update);
    }

    @Test
    public void test_health_ok() {
        assertThat(taskManager.getHealth()).isTrue();
    }

    @Test
    public void test_health_ko() throws Exception {
        AmqpQueue[] queues = {AmqpQueue.TASK, AmqpQueue.MANAGER_EVENT, AmqpQueue.MONITORING};
        AmqpInterlocutor amqpKo = new AmqpInterlocutor(new PropertiesProvider(new HashMap<>() {{
            put("messageBusAddress", "amqp://admin:admin@localhost?rabbitMq=false&monitoring=true");
        }}), queues);
        RedissonClient redissonClientKo = new RedissonClientFactory().withOptions(
                Options.from(new PropertiesProvider(Map.of("redisAddress", "redis://redis:6379")).getProperties())).create();
        TaskManagerAmqp taskManagerAmqpKo = new TaskManagerAmqp(amqpKo, new TaskRepositoryRedis(redissonClientKo, "tasks:queue:test"), RoutingStrategy.UNIQUE, () -> nextMessage.countDown());

        amqpKo.close();

        assertThat(taskManagerAmqpKo.getHealth()).isFalse();
    }

    @BeforeClass
    public static void beforeClass() throws Exception {
        AmqpQueue[] queues = {AmqpQueue.TASK, AmqpQueue.MANAGER_EVENT, AmqpQueue.MONITORING};
        AMQP = new AmqpInterlocutor(new PropertiesProvider(new HashMap<>() {{
            put("messageBusAddress", "amqp://admin:admin@localhost?rabbitMq=false&monitoring=true");
        }}), queues);
    }

    @Before
    public void setUp() throws IOException {
        nextMessage = new CountDownLatch(1);
        final RedissonClient redissonClient = new RedissonClientFactory().withOptions(
                Options.from(new PropertiesProvider(Map.of("redisAddress", "redis://redis:6379")).getProperties())).create();
        taskManager = new TaskManagerAmqp(AMQP, new TaskRepositoryRedis(redissonClient, "tasks:queue:test"), RoutingStrategy.UNIQUE, () -> nextMessage.countDown());
        taskSupplier = new TaskSupplierAmqp(AMQP);
        taskSupplier.consumeTasks(t -> taskQueue.add(t));
    }

    @After
    public void tearDown() throws Exception {
        taskQueue.clear();
        taskManager.clear();
        taskManager.stopTasks(User.local());
        taskSupplier.close();
        taskManager.close();
    }

    @AfterClass
    public static void afterClass() throws Exception {
        AMQP.close();
    }
}