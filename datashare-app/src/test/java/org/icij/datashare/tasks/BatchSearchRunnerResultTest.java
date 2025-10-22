package org.icij.datashare.tasks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskResult;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.user.User;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;

public class BatchSearchRunnerResultTest {
    @Test
    public void test_serialize() throws JsonProcessingException {
        BatchSearchRunnerResult batchRecord = new BatchSearchRunnerResult(10, 5);
        String json = JsonObjectMapper.writeValueAsString(batchRecord);

        assertThat(json).contains("\"nbResults\":10")
                .contains("\"nbQueriesWithoutResults\":5");
    }

    @Test
    public void test_deserialize() throws IOException {
        String json = "{\"nbResults\":10,\"nbQueriesWithoutResults\":5}";

        assertThat(JsonObjectMapper.readValue(json, BatchSearchRunnerResult.class)).isEqualTo(new BatchSearchRunnerResult(10, 5));
    }

    // Cannot be done in JooqTaskRepository since datashare-db does not know batch search runner
    // We set the same json deserialization context in TaskResult<BatchSearchRunnerResult>
    @Test
    public void test_serialize_deserialize_task() throws IOException {
        JavaType javaType = JsonObjectMapper.constructParametricType(Task.class, BatchSearchRunnerResult.class);

        Task<BatchSearchRunnerResult> task = new Task<>("test", User.local(), new HashMap<>());
        BatchSearchRunnerResult batchSearchRunnerResult = new BatchSearchRunnerResult(10, 5);
        task.setResult(new TaskResult<>(batchSearchRunnerResult));

        String json = JsonObjectMapper.writeValueAsString(task);

        assertThat(json).contains("\"nbResults\":10").contains("\"nbQueriesWithoutResults\":5");

        assertThat(((Task<?>)JsonObjectMapper.readValue(json, javaType)).getResult().value()).isEqualTo(batchSearchRunnerResult);
    }
}