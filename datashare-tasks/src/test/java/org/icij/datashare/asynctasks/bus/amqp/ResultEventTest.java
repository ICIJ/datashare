package org.icij.datashare.asynctasks.bus.amqp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;

public class ResultEventTest {
    @Test
    public void test_json_serialize_deserialize_result_event_error() throws Exception {
        RuntimeException throwable = new RuntimeException("this is an error", new RuntimeException("this is the cause"));
        ErrorEvent taskError = new ErrorEvent("task_id", new TaskError(throwable));

        ObjectMapper jsonMapper = MAPPER;
        String jsonError = jsonMapper.writeValueAsString(taskError);
        assertThat(jsonError).contains("\"@type\":\"TaskError\"");
        assertThat(jsonError).contains("this is an error");
        assertThat(jsonError).contains("this is the cause");

        ErrorEvent deserializedTaskErrorEvent = jsonMapper.readValue(jsonError, new TypeReference<>() {});
        assertThat(deserializedTaskErrorEvent.error).isEqualTo(taskError.error);
        assertThat(deserializedTaskErrorEvent.taskId).isEqualTo(taskError.taskId);
    }

    @Test
    public void test_json_serialize_deserialize_result_event_int() throws Exception {
        ResultEvent intResult = new ResultEvent("task_id", MAPPER.writeValueAsBytes(12));

        ObjectMapper jsonMapper = MAPPER;
        String jsonResult = jsonMapper.writeValueAsString(intResult);

        ResultEvent deserializedIntResult = jsonMapper.readValue(jsonResult, new TypeReference<>() {});
        assertThat(deserializedIntResult.result).isEqualTo(intResult.result);
        assertThat(deserializedIntResult.taskId).isEqualTo(intResult.taskId);
    }
}