package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;

public class TaskManagerRedisTest {
    PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
        put("redisAddress", "redis://redis:6379");
    }});
    private final BlockingQueue<TaskView<?>> batchDownloadQueue = new LinkedBlockingQueue<>();
    private final TaskManagerRedis taskManager = new TaskManagerRedis(propertiesProvider, "test:task:manager", batchDownloadQueue, this::callback);
    private final CountDownLatch waitForEvent = new CountDownLatch(1); // result event

    private final TaskSupplierRedis taskSupplier = new TaskSupplierRedis(propertiesProvider, batchDownloadQueue);

    @Test
    public void test_save_task() {
        TaskView<String> task = new TaskView<>("name", User.local(), new HashMap<>());

        taskManager.save(task);

        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTask(task.id)).isNotNull();
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
    }

    @Test
    public void test_done_tasks() throws Exception {
        TaskView<Integer> taskView = taskManager.startTask(TestTask.class.getName(), User.local(), new HashMap<>());

        assertThat(taskManager.getTasks()).hasSize(1);

        taskSupplier.result(taskView.id, 12);
        assertThat(waitForEvent.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(taskManager.getTasks().get(0).getState()).isEqualTo(TaskView.State.DONE);
        assertThat(taskManager.clearDoneTasks()).hasSize(1);
        assertThat(taskManager.getTasks()).hasSize(0);
    }

    @Test
    public void test_clear_task_among_two_tasks() throws Exception {
        TaskView<Integer> taskView1 = taskManager.startTask(TestTask.class.getName(), User.local(), new HashMap<>());
        TaskView<Integer> taskView2 = taskManager.startTask(TestTask.class.getName(), User.local(), new HashMap<>());

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
        BatchDownload batchDownload = new BatchDownload(singletonList(project("prj")), User.local(), "foo", null,Paths.get("/dir"), false);

        TaskView<UriResult> taskView = taskManager.startTask(BatchDownloadRunner.class.getName(), User.local(), new HashMap<>() {{
            put("batchDownload", batchDownload);
        }});
        UriResult result = new UriResult(new URI("/dir"), 123);
        taskSupplier.result(taskView.id, result);
        assertThat(waitForEvent.await(100, TimeUnit.SECONDS)).isTrue();

        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTasks().get(0).getResult()).isEqualTo(result);
    }

    private void callback() {
        waitForEvent.countDown();
    }

    @After
    public void tearDown() throws Exception {
        taskManager.clear();
    }
}
