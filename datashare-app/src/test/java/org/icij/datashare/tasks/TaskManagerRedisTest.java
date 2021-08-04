package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.junit.After;
import org.junit.Test;
import redis.clients.jedis.Jedis;

import java.util.HashMap;
import java.util.concurrent.ExecutionException;

import static org.fest.assertions.Assertions.assertThat;

public class TaskManagerRedisTest {
    private final Jedis redis = new Jedis("redis");
    PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<String, String>() {{
        put("redisAddress", "redis://redis:6379");
    }});
    private final TaskManagerRedis taskManager = new TaskManagerRedis(propertiesProvider);

    @Test
    public void test_save_task() {
        TaskView<String> task = new TaskView<>(new MonitorableFutureTask<>(() -> "run"));

        taskManager.save(task);

        assertThat(taskManager.get()).hasSize(1);
        assertThat(taskManager.get(task.name).name).isEqualTo(task.name);
    }

    @Test
    public void test_update_tasks() throws Exception {
        MonitorableFutureTask<String> futureTask = new MonitorableFutureTask<>(() -> "run");
        TaskView<String> task = new TaskView<>(futureTask);
        taskManager.save(task);

        futureTask.run();

        taskManager.save(new TaskView<>(futureTask));
        assertThat(taskManager.get(task.name).state).isEqualTo(TaskView.State.DONE);
    }

    @Test
    public void test_clear_done_tasks() {
        MonitorableFutureTask<String> futureTask = new MonitorableFutureTask<>(() -> "run");
        TaskView<String> task = new TaskView<>(futureTask);
        taskManager.save(task);

        assertThat(taskManager.clearDoneTasks()).hasSize(0);
        assertThat(taskManager.get()).hasSize(1);

        futureTask.run();
        taskManager.save(new TaskView<>(futureTask));

        assertThat(taskManager.clearDoneTasks()).hasSize(1);
    }

    @After
    public void tearDown() throws Exception {
        redis.del("ds:task:manager");
    }
}
