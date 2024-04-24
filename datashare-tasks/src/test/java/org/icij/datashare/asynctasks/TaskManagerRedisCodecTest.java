package org.icij.datashare.asynctasks;

import io.netty.buffer.Unpooled;
import org.fest.assertions.Assertions;
import org.icij.datashare.user.User;
import org.junit.Test;
import org.redisson.client.handler.State;

import java.nio.charset.Charset;
import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class TaskManagerRedisCodecTest {
    TaskManagerRedis.TaskViewCodec codec = new TaskManagerRedis.TaskViewCodec();

    @Test
    public void test_json_serialize_deserialize_with_inline_properties_map() throws Exception {
        TaskView<?> taskView = new TaskView<>("name", User.local(), new HashMap<>() {{
            put("key", "value");
        }});
        String json = codec.getValueEncoder().encode(taskView).toString(Charset.defaultCharset());

        assertThat(json).contains("\"key\":\"value\"");
        assertThat(json).contains("\"name\":\"name\"");

        TaskView<?> actualTask = (TaskView<?>) codec.getValueDecoder().decode(Unpooled.wrappedBuffer(json.getBytes()), new State());
        Assertions.assertThat(actualTask.name).isEqualTo("name");
        Assertions.assertThat(actualTask.properties).hasSize(1);
        Assertions.assertThat(actualTask.properties).includes(entry("key", "value"));
    }
}
