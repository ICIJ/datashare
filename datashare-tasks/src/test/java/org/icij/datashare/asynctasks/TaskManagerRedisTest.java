package org.icij.datashare.asynctasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.tasks.RoutingStrategy;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.redisson.RedissonBlockingQueue;
import org.redisson.api.RedissonClient;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;

public class TaskManagerRedisTest {
    PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
        put("redisAddress", "redis://redis:6379");
    }});
    private final RedissonClient redissonClient = new RedissonClientFactory().withOptions(
        Options.from(propertiesProvider.getProperties())).create();
    private final TaskManagerRedis taskManager = new TaskManagerRedis(
        redissonClient,
            "test:task:manager", RoutingStrategy.UNIQUE ,
            this::callback);

    private final CountDownLatch waitForEvent = new CountDownLatch(1); // result event

    private final TaskSupplierRedis
        taskSupplier = new TaskSupplierRedis(redissonClient);

    @Test
    public void test_save_task() throws TaskAlreadyExists, IOException {
        Task<String> task = new Task<>("name", new HashMap<>());

        taskManager.save(task, null);

        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTask(task.id)).isNotNull();
    }

    @Test
    public void test_update_task() throws TaskAlreadyExists, IOException {
        // Given
        Task<?> task = new Task<>("HelloWorld", Map.of("greeted", "world"));
        TaskMetadata<?> meta = new TaskMetadata<>(task, null);
        Task<?> update = new Task<>(task.id, task.name, task.getState(), 0.5, null, task.args);
        // When
        taskManager.saveMetadata(meta);
        taskManager.update(update);
        Task<?> updated = taskManager.getTask(task.id);
        // Then
        assertThat(updated).isEqualTo(update);
    }

    @Test
    public void test_start_task() throws IOException {
        String taskId = taskManager.startTask("HelloWorld", Map.of("greeted", "world"));
        assertThat(taskId).isNotNull();
        assertThat(taskManager.getTasks()).hasSize(1);
    }

    @Test
    public void test_start_task_with_group_routing() throws Exception {
        try (TaskManagerRedis groupTaskManager = new TaskManagerRedis(
                redissonClient,
                "test:task:manager", RoutingStrategy.GROUP,
                this::callback);
            TaskSupplierRedis taskSupplier = new TaskSupplierRedis(redissonClient, "Group")) {

            assertThat(groupTaskManager.startTask("HelloWorld", new Group("Group"),Map.of("greeted", "world"))).isNotNull();

            Task<Serializable> task = taskSupplier.get(2, TimeUnit.SECONDS);
            assertThat(groupTaskManager.getTaskGroup(task.id)).isEqualTo(new Group("Group"));
            assertThat(((RedissonBlockingQueue<?>) groupTaskManager.taskQueue(task)).getName()).isEqualTo("TASK.Group");
        }
    }

    @Test
    public void test_start_task_with_name_routing() throws Exception {
        try (TaskManagerRedis nameTaskManager = new TaskManagerRedis(
                redissonClient,
                "test:task:manager", RoutingStrategy.NAME,
                this::callback);
             TaskSupplierRedis taskSupplier = new TaskSupplierRedis(redissonClient, "HelloWorld")) {
            assertThat(nameTaskManager.startTask("HelloWorld", Map.of("greeted", "world"))).isNotNull();

            Task<Serializable> task = taskSupplier.get(2, TimeUnit.SECONDS);
            assertThat(((RedissonBlockingQueue<?>) nameTaskManager.taskQueue(task)).getName()).isEqualTo("TASK.HelloWorld");
        }
    }

    @Test
    @Ignore("remove is async and clearTasks is not always done before the size is changed")
    public void test_done_tasks() throws Exception {
        String taskViewId = taskManager.startTask("sleep", new HashMap<>());

        assertThat(taskManager.getTasks()).hasSize(1);
        System.out.println(taskManager.getTasks());

        taskSupplier.result(taskViewId, 12);
        assertThat(waitForEvent.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(taskManager.getTasks().get(0).getState()).isEqualTo(Task.State.DONE);
        assertThat(taskManager.clearDoneTasks()).hasSize(1);
        assertThat(taskManager.getTasks()).hasSize(0);
    }

    @Test
    @Ignore("remove is async and clearTasks is not always done before the size is changed")
    public void test_clear_task_among_two_tasks() throws Exception {
        String taskView1Id = taskManager.startTask("sleep", new HashMap<>());
        String taskView2Id = taskManager.startTask("sleep", new HashMap<>());

        taskSupplier.result(taskView1Id, 123);
        assertThat(waitForEvent.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(taskManager.getTasks()).hasSize(2);
        Task<?> clearedTask = taskManager.clearTask(taskView1Id);
        assertThat(taskView1Id).isEqualTo(clearedTask.id);
        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTask(taskView1Id)).isNull();
        assertThat(taskManager.getTask(taskView2Id)).isNotNull();
    }

    @Test(expected = IllegalStateException.class)
    public void test_clear_running_task_should_throw_exception() throws Exception {
        String taskViewId = taskManager.startTask("sleep", new HashMap<>());

        taskSupplier.progress(taskViewId,0.5);
        assertThat(waitForEvent.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(taskManager.getTask(taskViewId).getState()).isEqualTo(Task.State.RUNNING);

        taskManager.clearTask(taskViewId);
    }

    @Test
    public void test_done_task_result_for_file() throws Exception {
        String taskViewId = taskManager.startTask("HelloWorld", new HashMap<>() {{
                put("greeted", "world");
            }});
        String expectedResult = "Hello world !";
        taskSupplier.result(taskViewId, expectedResult);
        assertThat(waitForEvent.await(100, TimeUnit.SECONDS)).isTrue();

        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTasks().get(0).getResult()).isEqualTo(expectedResult);
    }

    private void callback() {
        waitForEvent.countDown();
    }

    @After
    public void tearDown() {
        taskManager.clear();
    }
    @Before
    public void setUp() {
        taskManager.clear();
    }
}
