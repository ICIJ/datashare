package org.icij.datashare.tasks;

import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.user.User;
import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.fest.assertions.Assertions.assertThat;

public class TaskViewTest {
    private final Executor executor = Executors.newSingleThreadExecutor();

    @Test
    public void test_get_result_sync_when_task_is_done() {
        MonitorableFutureTask<String> task = new MonitorableFutureTask<>(() -> "run");
        task.run();
        TaskView<String> taskView = new TaskView<>(task);

        assertThat(taskView.getResult()).isEqualTo("run");
        assertThat(taskView.getProgress()).isEqualTo(1);
        assertThat(taskView.getState()).isEqualTo(TaskView.State.DONE);
    }

    @Test
    public void test_get_result_sync_when_task_is_running() {
        MonitorableFutureTask<String> task = new MonitorableFutureTask<>(() -> {
            Thread.sleep(100);
            return "run";
        });
        executor.execute(task);
        TaskView<String> taskView = new TaskView<>(task);

        assertThat(taskView.getProgress()).isEqualTo(-2);
        assertThat(taskView.getState()).isEqualTo(TaskView.State.RUNNING);
        assertThat(taskView.getResult(false)).isNull();
        assertThat(taskView.getResult(true)).isEqualTo("run");
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
        assertThat(taskView.getState()).isEqualTo(TaskView.State.INIT);

        taskView.setProgress(0.0);
        assertThat(taskView.getState()).isEqualTo(TaskView.State.INIT);

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
                "\"name\":\"org.icij.datashare.tasks.BatchDownloadRunner\",\"state\":\"INIT\"," +
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