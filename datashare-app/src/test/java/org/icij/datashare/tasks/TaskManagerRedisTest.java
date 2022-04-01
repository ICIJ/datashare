package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Test;
import redis.clients.jedis.Jedis;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;

import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.mockito.Mockito.mock;

public class TaskManagerRedisTest {
    private final Jedis redis = new Jedis("redis");
    PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<String, String>() {{
        put("redisAddress", "redis://redis:6379");
    }});
    private final BlockingQueue<BatchDownload> batchDownloadQueue = new LinkedBlockingQueue<>();
    private final TaskManagerRedis taskManager = new TaskManagerRedis(propertiesProvider, "test:task:manager", batchDownloadQueue);

    @Test
    public void test_save_task() {
        TaskView<String> task = new TaskView<>(new MonitorableFutureTask<>(() -> "run"));

        taskManager.save(task);

        assertThat(taskManager.get()).hasSize(1);
        assertThat(taskManager.get(task.name).name).isEqualTo(task.name);
    }

    @Test
    public void test_save_failing_task_subtype_throwable() throws Exception {
        MonitorableFutureTask<String> test_exception = new MonitorableFutureTask<>(() -> {
            RuntimeException runtimeException = new RuntimeException("test exception");
            runtimeException.addSuppressed(new RuntimeException("suppressed"));
            throw runtimeException;
        });
        try {
            test_exception.run();
            test_exception.get();
        } catch (ExecutionException rex) {
            taskManager.save(new TaskView<>(test_exception));
        }
        List<TaskView<?>> actual = taskManager.get();
        assertThat(actual).hasSize(1);
        assertThat(actual.get(0).getState()).isEqualTo(TaskView.State.ERROR);
    }

    @Test
    public void test_update_tasks() {
        MonitorableFutureTask<String> futureTask = new MonitorableFutureTask<>(() -> "run");
        TaskView<String> task = new TaskView<>(futureTask);
        taskManager.save(task);

        futureTask.run();

        taskManager.save(new TaskView<>(futureTask));
        assertThat(taskManager.get(task.name).getState()).isEqualTo(TaskView.State.DONE);
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

    @Test
    public void test_start_task() {
        BatchDownload batchDownload = new BatchDownload(singletonList(project("prj")), User.local(), "foo", Paths.get("dir"), false);
        BatchDownloadRunner downloadTask = new BatchDownloadRunner(mock(Indexer.class), propertiesProvider, batchDownload, t -> null);

        assertThat(taskManager.startTask(downloadTask, new HashMap<String, Object>() {{ put("batchDownload", batchDownload);}})).isNotNull();
        assertThat(taskManager.get()).hasSize(1);
        assertThat(batchDownloadQueue).hasSize(1);
        assertThat(redis.hlen("test:task:manager")).isEqualTo(1);
    }

    @After
    public void tearDown() throws Exception {
        redis.del("test:task:manager");
    }
}
