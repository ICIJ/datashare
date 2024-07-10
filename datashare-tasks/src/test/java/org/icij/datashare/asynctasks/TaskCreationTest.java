package org.icij.datashare.asynctasks;

import org.icij.datashare.asynctasks.bus.amqp.TaskCreation;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.user.User;
import org.junit.Test;

import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class TaskCreationTest {
    @Test
    public void test_serialize_deserialize() throws Exception {
        TaskView<Object> taskView = new TaskView<>("name", User.local(), Map.of("key", "value"));
        TaskCreation expectedValue = new TaskCreation(taskView);
        String json = JsonObjectMapper.MAPPER.writeValueAsString(expectedValue);
        System.out.println(json);
        assertThat(json).contains("\"@type\":\"TaskCreation\"");
        assertThat(json).doesNotContain("\"taskCreation\":{"); // do not nest taskView

        TaskCreation taskCreation = JsonObjectMapper.MAPPER.readValue(json, TaskCreation.class);
        assertThat(taskCreation.taskView).isEqualTo(taskView);
        assertThat(taskCreation.createdAt).isEqualTo(expectedValue.createdAt);
    }
}
