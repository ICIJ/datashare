package org.icij.datashare.asynctasks;

import org.fest.assertions.Assertions;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.junit.After;
import org.junit.Test;
import org.redisson.api.RedissonClient;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;

public class TaskManagerRedisTest {
    PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
        put("redisAddress", "redis://redis:6379");
    }});
    private final BlockingQueue<TaskView<?>> batchDownloadQueue = new LinkedBlockingQueue<>();
    private final RedissonClient redissonClient = new RedissonClientFactory().withOptions(
        Options.from(propertiesProvider.getProperties())).create();
    private final TaskManagerRedis taskManager = new TaskManagerRedis(
        redissonClient,
        batchDownloadQueue,
        "test:task:manager",
        this::callback
    );

    private final CountDownLatch waitForEvent = new CountDownLatch(1); // result event

    private final TaskSupplierRedis
        taskSupplier = new TaskSupplierRedis(redissonClient, batchDownloadQueue);

    @Test
    public void test_save_task() {
        TaskView<String> task = new TaskView<>("name", User.local(), new HashMap<>());

        taskManager.save(task);

        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTask(task.id)).isNotNull();
    }

    @Test
    public void test_start_task() throws IOException {
        assertThat(taskManager.startTask("HelloWorld", User.local(),
            new HashMap<>() {{ put("greeted", "world"); }})).isNotNull();
        assertThat(taskManager.getTasks()).hasSize(1);
        Assertions.assertThat(taskManager.getTasks().get(0).getUser()).isEqualTo(User.local());
        assertThat(batchDownloadQueue).hasSize(1);
    }

    @Test
    public void test_done_tasks() throws Exception {
        TaskView<Integer> taskView = taskManager.startTask("sleep", User.local(), new HashMap<>());

        assertThat(taskManager.getTasks()).hasSize(1);

        taskSupplier.result(taskView.id, 12);
        assertThat(waitForEvent.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(taskManager.getTasks().get(0).getState()).isEqualTo(TaskView.State.DONE);
        assertThat(taskManager.clearDoneTasks()).hasSize(1);
        assertThat(taskManager.getTasks()).hasSize(0);
    }

    @Test
    public void test_clear_task_among_two_tasks() throws Exception {
        TaskView<Integer> taskView1 = taskManager.startTask("sleep", User.local(), new HashMap<>());
        TaskView<Integer> taskView2 = taskManager.startTask("sleep", User.local(), new HashMap<>());

        taskSupplier.result(taskView1.id, 123);
        assertThat(waitForEvent.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(taskManager.getTasks()).hasSize(2);
        TaskView<?> clearedTask = taskManager.clearTask(taskView1.id);
        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTask(taskView1.id)).isNull();
        assertThat(taskManager.getTask(taskView2.id)).isNotNull();
        assertThat(taskView1.id).isEqualTo(clearedTask.id);
    }

    @Test
    public void test_done_task_result_for_file() throws Exception {
        TaskView<String> taskView = taskManager.startTask("HelloWorld", User.local(), new HashMap<>() {{
                put("greeted", "world");
            }});
        String expectedResult = "Hello world !";
        taskSupplier.result(taskView.id, expectedResult);
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
