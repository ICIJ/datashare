package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Test;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;

import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;

public class TaskManagerRedisTest {
    private final Jedis redis = new Jedis("redis");
    PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
        put("redisAddress", "redis://redis:6379");
    }});
    private final BlockingQueue<TaskView<?>> batchDownloadQueue = new LinkedBlockingQueue<>();
    private final TaskManagerRedis taskManager = new TaskManagerRedis(propertiesProvider, "test:task:manager", batchDownloadQueue, true);

    @Test
    public void test_save_task() {
        TaskView<String> task = new TaskView<>("name", User.local(), new HashMap<>());

        taskManager.save(task);

        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTask(task.id)).isNotNull();
    }

    @Test
    public void test_clear_done_tasks() throws Exception {
        TaskView<Integer> taskView = taskManager.startTask(TestTask.class.getName(), User.local(), new HashMap<>());

        assertThat(taskManager.clearDoneTasks()).hasSize(0);
        assertThat(taskManager.getTasks()).hasSize(1);

        taskManager.result(taskView.id, 12);

        assertThat(taskManager.clearDoneTasks()).hasSize(1);
    }

    @Test
    public void test_start_task() throws IOException {
        BatchDownload batchDownload = new BatchDownload(singletonList(project("prj")), User.local(), "foo", null,Paths.get("dir"), false);

        assertThat(taskManager.startTask(BatchDownloadRunner.class.getName(), User.local(), new HashMap<>() {{
            put("batchDownload", batchDownload);
        }})).isNotNull();
        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTasks().get(0).user).isEqualTo(User.local());
        assertThat(batchDownloadQueue).hasSize(1);
        assertThat(redis.hlen("test:task:manager")).isEqualTo(1);
    }

    @Test
    public void test_clear_task_among_two_tasks() throws IOException {
        TaskView<Integer> taskView1 = taskManager.startTask(TestTask.class.getName(), User.local(), new HashMap<>());
        TaskView<Integer> taskView2 = taskManager.startTask(TestTask.class.getName(), User.local(), new HashMap<>());

        taskManager.result(taskView1.id, 123);

        assertThat(taskManager.getTasks()).hasSize(2);
        TaskView<?> clearedTask = taskManager.clearTask(taskView1.id);
        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTask(taskView1.id)).isNull();
        assertThat(taskManager.getTask(taskView2.id)).isNotNull();
        assertThat(taskView1.id).isEqualTo(clearedTask.id);
    }

    @After
    public void tearDown() throws Exception {
        redis.del("test:task:manager");
    }

    static class TestTask implements CancellableCallable<Integer> {
        @Override
        public void cancel(String taskId, boolean requeue) {}
        @Override
        public Integer call() throws Exception {return 0;}
    }
}
