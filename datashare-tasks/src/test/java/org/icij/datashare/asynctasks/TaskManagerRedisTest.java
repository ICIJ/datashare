package org.icij.datashare.asynctasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.tasks.RoutingStrategy;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.junit.After;
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
    public void test_save_task() {
        Task<String> task = new Task<>("name", User.local(), new HashMap<>());

        taskManager.save(task);

        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTask(task.id)).isNotNull();
    }

    @Test
    public void test_start_task() throws IOException {
        assertThat(taskManager.startTask("HelloWorld", User.local(),
            new HashMap<>() {{ put("greeted", "world"); }})).isNotNull();
        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTasks().get(0).getUser()).isEqualTo(User.local());
    }

    @Test
    public void test_start_task_with_group_routing() throws Exception {
        try (TaskManagerRedis groupTaskManager = new TaskManagerRedis(
                redissonClient,
                "test:task:manager", RoutingStrategy.GROUP,
                this::callback);
            TaskSupplierRedis taskSupplier = new TaskSupplierRedis(redissonClient, "Group")) {

            assertThat(groupTaskManager.startTask("HelloWorld", User.local(), new Group("Group"),Map.of("greeted", "world"))).isNotNull();

            Task<Serializable> task = taskSupplier.get(2, TimeUnit.SECONDS);
            assertThat(task.getGroup()).isEqualTo(new Group("Group"));
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
            assertThat(nameTaskManager.startTask("HelloWorld", User.local(), Map.of("greeted", "world"))).isNotNull();

            Task<Serializable> task = taskSupplier.get(2, TimeUnit.SECONDS);
            assertThat(((RedissonBlockingQueue<?>) nameTaskManager.taskQueue(task)).getName()).isEqualTo("TASK.HelloWorld");
        }
    }

    @Test
    public void test_done_tasks() throws Exception {
        String taskViewId = taskManager.startTask("sleep", User.local(), new HashMap<>());

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
        String taskView1Id = taskManager.startTask("sleep", User.local(), new HashMap<>());
        String taskView2Id = taskManager.startTask("sleep", User.local(), new HashMap<>());

        taskSupplier.result(taskView1Id, 123);
        assertThat(waitForEvent.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(taskManager.getTasks()).hasSize(2);
        Task<?> clearedTask = taskManager.clearTask(taskView1Id);
        assertThat(taskView1Id).isEqualTo(clearedTask.id);
        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTask(taskView1Id)).isNull();
        assertThat(taskManager.getTask(taskView2Id)).isNotNull();
    }

    @Test
    public void test_done_task_result_for_file() throws Exception {
        String taskViewId = taskManager.startTask("HelloWorld", User.local(), new HashMap<>() {{
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
}
