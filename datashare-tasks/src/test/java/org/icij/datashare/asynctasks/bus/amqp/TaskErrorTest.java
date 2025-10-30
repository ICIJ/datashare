package org.icij.datashare.asynctasks.bus.amqp;

import com.fasterxml.jackson.core.type.TypeReference;
import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class TaskErrorTest {
    @Test
    public void test_stacktrace_item_to_string() {
        RuntimeException throwable = new RuntimeException("this is an error");
        TaskError errorResult = new TaskError(throwable);
        assertThat(errorResult.stacktrace.get(0).toString()).isEqualTo(String.format("\tat %s.%s:%d", getClass().getName(), "test_stacktrace_item_to_string", 12));
        assertThat(errorResult.stacktrace.get(1).toString()).isEqualTo("\tat jdk.internal.reflect.NativeMethodAccessorImpl (native method)");
    }

    @Test
    public void test_task_error_to_string() {
        RuntimeException throwable = new RuntimeException("this is an error", new RuntimeException("this is the cause"));
        TaskError taskError = new TaskError(throwable);
        assertThat(taskError.toString()).contains("this is an error");
        assertThat(taskError.toString()).contains("this is the cause");
    }

    @Test
    public void test_constructor_from_throwable() {
        RuntimeException throwable = new RuntimeException("this is an error");
        TaskError taskError = new TaskError(throwable);
        assertThat(taskError.name).isEqualTo("java.lang.RuntimeException");
        assertThat(taskError.message).isEqualTo("this is an error");
        assertThat(taskError.cause).isNull();
        assertThat(taskError.stacktrace.size()).isGreaterThan(20);
        for (TaskError.StacktraceItem stItem: taskError.stacktrace) {
            assertThat(stItem.file).isNotNull();
            assertThat(stItem.lineno).isNotEqualTo(0);
            assertThat(stItem.name).isNotEmpty();
        }
    }

    @Test
    public void test_constructor_from_throwable_with_cause() {
        RuntimeException throwable = new RuntimeException("this is an error", new RuntimeException("this is the cause"));
        TaskError taskError = new TaskError(throwable);
        assertThat(taskError.cause).isEqualTo("java.lang.RuntimeException: this is the cause");
    }

    @Test
    public void test_json_serialize_deserialize_task_error() throws Exception {
        RuntimeException throwable = new RuntimeException("this is an error", new RuntimeException("this is the cause"));
        TaskError taskError = new TaskError(throwable);
        String jsonError = JsonObjectMapper.writeValueAsString(taskError);
        assertThat(jsonError).contains("\"@type\":\"TaskError\"");
        assertThat(jsonError).contains("this is an error");
        assertThat(jsonError).contains("this is the cause");

        TaskError deserializedTaskError = JsonObjectMapper.readValue(jsonError, new TypeReference<>() {});
        assertThat(deserializedTaskError).isEqualTo(taskError);
    }
}