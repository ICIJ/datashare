package org.icij.datashare.asynctasks;

import org.fest.assertions.Assertions;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.user.User;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class TaskTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Test
    public void test_get_result_sync_when_task_is_running() throws InterruptedException {
        Task<String> taskView = new Task<>("name", User.local(), new HashMap<>());
        executor.execute(() -> {
            try {
                taskView.getResult(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        taskView.setResult(new TaskResult<>("foo"));
        executor.shutdownNow();
        assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        assertThat(taskView.getProgress()).isEqualTo(1);
        assertThat(taskView.getResult()).isEqualTo(new TaskResult<>("foo"));
        assertThat(taskView.getState()).isEqualTo(Task.State.DONE);
    }

    @Test
    public void test_user_parameter() {
        Task<String> taskView = new Task<>("foo", User.local(), Map.of("baz", "qux"));
        assertThat(taskView.args).includes(entry("user", User.local()), entry("baz", "qux"));
    }

    @Test
    public void test_progress() {
        Task<String> taskView = new Task<>("name", User.local(), new HashMap<>());
        assertThat(taskView.getProgress()).isEqualTo(0);
        assertThat(taskView.getState()).isEqualTo(Task.State.CREATED);

        taskView.setProgress(0.0);
        assertThat(taskView.getState()).isEqualTo(Task.State.RUNNING);

        taskView.setProgress(0.3);
        assertThat(taskView.getProgress()).isEqualTo(0.3);
        assertThat(taskView.getState()).isEqualTo(Task.State.RUNNING);
    }

    @Test
    public void test_json_deserialize() throws Exception {
        String json = "{\"@type\":\"Task\",\"id\":\"d605de70-dc8d-429f-8b22-1cc3e9157756\"," +
                "\"name\":\"HelloWorld\",\"state\":\"CREATED\"," +
                "\"progress\":0.0,\"user\":{\"id\":\"local\",\"name\":null,\"email\":null," +
                "\"provider\":\"local\"},\"properties\":" +
                "{\"batchDownload\":{\"uuid\":\"6dead06a-96bd-441b-aa86-76ba0532e71f\"," +
                "\"projects\":[{\"name\":\"test-datashare\",\"sourcePath\":\"file:///vault/test-datashare\"," +
                "\"label\":\"test-datashare\",\"description\":null,\"publisherName\":null," +
                "\"maintainerName\":null,\"logoUrl\":null,\"sourceUrl\":null,\"creationDate\":null,\"updateDate\":null}]," +
                "\"filename\":\"file:///home/dev/src/datashare/datashare-app/app/tmp/archive_local_2021-07-07T12_23_34Z%5BGMT%5D.zip\"," +
                "\"query\":\"*\",\"uri\":null,\"user\":{\"id\":\"local\",\"name\":null,\"email\":null,\"provider\":\"local\"}," +
                "\"encrypted\":false,\"zipSize\":0,\"exists\":false}}}";
        Task<?> taskView = JsonObjectMapper.MAPPER.readValue(json, Task.class);
        Assertions.assertThat(taskView.name).isEqualTo("HelloWorld");
        Assertions.assertThat(taskView.id).isEqualTo("d605de70-dc8d-429f-8b22-1cc3e9157756");
    }

    @Test
    public void test_serialize_deserialize() throws Exception {
        Task<String> taskView = new Task<>("name", User.local(), Map.of("key", "value"));
        String json = JsonObjectMapper.MAPPER.writeValueAsString(taskView);
        assertThat(json).contains("\"@type\":\"Task\"");
        assertThat(json).contains("\"user\":{\"@type\":\"org.icij.datashare.user.User\"");

        Task<?> taskCreation = JsonObjectMapper.MAPPER.readValue(json, Task.class);
        assertThat(taskCreation).isEqualTo(taskView);
        assertThat(taskCreation.createdAt).isEqualTo(taskCreation.createdAt);
    }
}