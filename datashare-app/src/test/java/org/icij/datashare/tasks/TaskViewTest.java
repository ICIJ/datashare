package org.icij.datashare.tasks;

import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.user.User;
import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;

public class TaskViewTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Test
    public void test_get_result_sync_when_task_is_running() throws InterruptedException {
        TaskView<String> taskView = new TaskView<>("name", User.local(), new HashMap<>());
        executor.execute(() -> {
            try {
                taskView.getResult(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        taskView.setResult("foo");
        executor.shutdownNow();
        assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        assertThat(taskView.getProgress()).isEqualTo(1);
        assertThat(taskView.getResult()).isEqualTo("foo");
        assertThat(taskView.getState()).isEqualTo(TaskView.State.DONE);
    }

    @Test
    public void test_get_result_sync_when_task_is_not_local() {
        TaskView<Object> taskView = new TaskView<>("id", "task", TaskView.State.DONE, 1, User.local(), null, new HashMap<>());
        assertThat(taskView.getResult()).isNull();
        assertThat(taskView.getState()).isEqualTo(TaskView.State.DONE);
        assertThat(taskView.getProgress()).isEqualTo(1);
    }

    @Test
    public void test_progress() {
        TaskView<Object> taskView = new TaskView<>("name", User.local(), new HashMap<>());
        assertThat(taskView.getProgress()).isEqualTo(0);
        assertThat(taskView.getState()).isEqualTo(TaskView.State.CREATED);

        taskView.setProgress(0.0);
        assertThat(taskView.getState()).isEqualTo(TaskView.State.RUNNING);

        taskView.setProgress(0.3);
        assertThat(taskView.getProgress()).isEqualTo(0.3);
        assertThat(taskView.getState()).isEqualTo(TaskView.State.RUNNING);
    }

    @Test
    public void test_get_result_sync_when_task_is_not_local_and_result_is_not_null() {
        TaskView<Object> taskView = new TaskView<>("id", "task", TaskView.State.DONE, 1, User.local(), "run", new HashMap<>());
        assertThat(taskView.getResult()).isEqualTo("run");
    }

    @Test
    public void test_json_deserialize() throws Exception {
        String json = "{\"id\":\"d605de70-dc8d-429f-8b22-1cc3e9157756\"," +
                "\"name\":\"org.icij.datashare.tasks.BatchDownloadRunner\",\"state\":\"CREATED\"," +
                "\"progress\":0.0,\"user\":{\"id\":\"local\",\"name\":null,\"email\":null," +
                "\"provider\":\"local\"},\"properties\":" +
                "{\"batchDownload\":{\"uuid\":\"6dead06a-96bd-441b-aa86-76ba0532e71f\"," +
                "\"projects\":[{\"name\":\"test-datashare\",\"sourcePath\":\"file:///vault/test-datashare\"," +
                "\"label\":\"test-datashare\",\"description\":null,\"publisherName\":null," +
                "\"maintainerName\":null,\"logoUrl\":null,\"sourceUrl\":null,\"creationDate\":null,\"updateDate\":null}]," +
                "\"filename\":\"file:///home/dev/src/datashare/datashare-app/app/tmp/archive_local_2021-07-07T12_23_34Z%5BGMT%5D.zip\"," +
                "\"query\":\"*\",\"uri\":null,\"user\":{\"id\":\"local\",\"name\":null,\"email\":null,\"provider\":\"local\"}," +
                "\"encrypted\":false,\"zipSize\":0,\"exists\":false}}}";
        TaskView<?> taskView = JsonObjectMapper.MAPPER.readValue(json, TaskView.class);
        assertThat(taskView.name).isEqualTo(BatchDownloadRunner.class.getName());
        assertThat(taskView.id).isEqualTo("d605de70-dc8d-429f-8b22-1cc3e9157756");
    }
}