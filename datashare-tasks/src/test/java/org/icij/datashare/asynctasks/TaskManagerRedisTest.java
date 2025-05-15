package org.icij.datashare.asynctasks;

import java.util.List;
import java.util.stream.Stream;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.tasks.RoutingStrategy;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.redisson.RedissonBlockingQueue;
import org.redisson.api.RedissonClient;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;

public class TaskManagerRedisTest {
    PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
        put("redisAddress", "redis://redis:6379");
    }});
    private final RedissonClient redissonClient = new RedissonClientFactory().withOptions(
        Options.from(propertiesProvider.getProperties())).create();
    private final TaskManagerRedis taskManager = new TaskManagerRedis(
        redissonClient,
            new TaskRepositoryRedis(redissonClient, "test:task:manager"), RoutingStrategy.UNIQUE ,
            this::callback);

    private final CountDownLatch waitForEvent = new CountDownLatch(1); // result event

    private final TaskSupplierRedis
        taskSupplier = new TaskSupplierRedis(redissonClient);

    @Test
    public void test_persist_task() throws TaskAlreadyExists, IOException, UnknownTask {
        Task task = new Task("name", User.local(), new HashMap<>());

        taskManager.insert(task, new Group(TaskGroupType.Test));

        List<Task> tasks = taskManager.getTasks().toList();
        assertThat(tasks).hasSize(1);
        assertThat(taskManager.getTask(task.id)).isNotNull();
    }

    @Test
    public void test_update_task() throws TaskAlreadyExists, IOException, UnknownTask {
        // Given
        Task task = new Task("HelloWorld", User.local(), Map.of("greeted", "world"));
        Task update = new Task(task.id, task.name, task.getState(), 0.5, null, 3, null, task.args, null, null);
        // When
        taskManager.insert(task, null);
        taskManager.update(update);
        Task updated = taskManager.getTask(task.id);
        // Then
        assertThat(updated).isEqualTo(update);
    }

    @Test
    public void test_start_task() throws IOException {
        assertThat(taskManager.startTask("HelloWorld", User.local(),
            new HashMap<>() {{ put("greeted", "world"); }})).isNotNull();
        assertThat(taskManager.getTasks().toList()).hasSize(1);
    }

    @Test
    public void test_start_task_with_group_routing() throws Exception {
        try (TaskManagerRedis groupTaskManager = new TaskManagerRedis(
                redissonClient,
                new TaskRepositoryRedis(redissonClient, "test:task:manager"), RoutingStrategy.GROUP,
                this::callback);
            TaskSupplierRedis taskSupplier = new TaskSupplierRedis(redissonClient, TaskGroupType.Test.name())) {

            assertThat(groupTaskManager.startTask("HelloWorld", User.local(), new Group(TaskGroupType.Test), Map.of("greeted", "world"))).isNotNull();

            Task task = taskSupplier.get(2, TimeUnit.SECONDS);
            assertThat(taskManager.getTaskGroup(task.id)).isEqualTo(new Group(TaskGroupType.Test));
            assertThat(((RedissonBlockingQueue<?>) groupTaskManager.taskQueue(task)).getName()).isEqualTo("TASK.Test");
        }
    }

    @Test
    public void test_start_task_with_name_routing() throws Exception {
        try (TaskManagerRedis nameTaskManager = new TaskManagerRedis(
                redissonClient,
                new TaskRepositoryRedis(redissonClient, "test:task:manager"), RoutingStrategy.NAME,
                this::callback);
             TaskSupplierRedis taskSupplier = new TaskSupplierRedis(redissonClient, "HelloWorld")) {
            assertThat(nameTaskManager.startTask("HelloWorld", User.local(), Map.of("greeted", "world"))).isNotNull();

            Task task = taskSupplier.get(2, TimeUnit.SECONDS);
            assertThat(((RedissonBlockingQueue<?>) nameTaskManager.taskQueue(task)).getName()).isEqualTo("TASK.HelloWorld");
        }
    }

    @Test
    @Ignore("remove is async and clearTasks is not always done before the size is changed")
    public void test_done_tasks() throws Exception {
        String taskViewId = taskManager.startTask("sleep", User.local(), new HashMap<>());

        assertThat(taskManager.getTasks().toList()).hasSize(1);

        taskSupplier.result(taskViewId, MAPPER.writeValueAsBytes(12));
        assertThat(waitForEvent.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(taskManager.getTasks().findFirst().get().getState()).isEqualTo(Task.State.DONE);
        assertThat(taskManager.clearDoneTasks()).hasSize(1);
        assertThat(taskManager.getTasks().toList()).hasSize(0);
    }

    @Test
    @Ignore("remove is async and clearTasks is not always done before the size is changed")
    public void test_clear_task_among_two_tasks() throws Exception {
        String taskView1Id = taskManager.startTask("sleep", User.local(), new HashMap<>());
        String taskView2Id = taskManager.startTask("sleep", User.local(), new HashMap<>());

        taskSupplier.result(taskView1Id, MAPPER.writeValueAsBytes(123));
        assertThat(waitForEvent.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(taskManager.getTasks().toList()).hasSize(2);
        Task clearedTask = taskManager.clearTask(taskView1Id);
        assertThat(taskView1Id).isEqualTo(clearedTask.id);
        assertThat(taskManager.getTasks().toList()).hasSize(1);
        assertThat(taskManager.getTask(taskView1Id)).isNull();
        assertThat(taskManager.getTask(taskView2Id)).isNotNull();
    }

    @Test(expected = IllegalStateException.class)
    public void test_clear_running_task_should_throw_exception() throws Exception {
        String taskViewId = taskManager.startTask("sleep", User.local(), new HashMap<>());

        taskSupplier.progress(taskViewId,0.5);
        assertThat(waitForEvent.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(taskManager.getTask(taskViewId).getState()).isEqualTo(Task.State.RUNNING);

        taskManager.clearTask(taskViewId);
    }

    @Test(timeout = 10000)
    public void test_done_task_result_for_file() throws Exception {
        String taskViewId = taskManager.startTask("HelloWorld", User.local(), new HashMap<>() {{
                put("greeted", "world");
            }});
        byte[] expectedResult = MAPPER.writeValueAsBytes(12);
        taskSupplier.result(taskViewId, expectedResult);
        assertThat(waitForEvent.await(100, TimeUnit.SECONDS)).isTrue();

        List<Task> tasks = taskManager.getTasks().toList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getResult()).isEqualTo(expectedResult);
    }

    @Test
    public void test_health_ok() {
        assertThat(taskManager.getHealth()).isTrue();
    }

    @Test
    public void test_health_ko(){
        RedissonClient redissonClientKO = new RedissonClientFactory().withOptions(
                Options.from(propertiesProvider.getProperties())).create();
        TaskManagerRedis taskManager = new TaskManagerRedis(
                redissonClientKO, new TaskRepositoryRedis(redissonClientKO, "test:task:manager"), RoutingStrategy.UNIQUE, this::callback);
        redissonClientKO.shutdown();

        assertThat(taskManager.getHealth()).isFalse();
    }

    private void callback() {
        waitForEvent.countDown();
    }

    @After
    public void tearDown() throws IOException {
        taskManager.clear();
    }
    @Before
    public void setUp() throws IOException {
        taskManager.clear();
    }
}
