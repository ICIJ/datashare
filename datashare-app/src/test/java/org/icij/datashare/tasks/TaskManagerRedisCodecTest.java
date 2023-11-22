package org.icij.datashare.tasks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.redisson.client.handler.State;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;

import static io.netty.buffer.Unpooled.wrappedBuffer;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class TaskManagerRedisCodecTest {
    TaskManagerRedis.TaskViewCodec codec = new TaskManagerRedis.TaskViewCodec();



    @Test
    @Ignore
    public void test_json_serialize_deserialize_with_inline_properties_map() throws Exception {
        TaskView<?> taskView = new TaskView<>("name", User.local(), new HashMap<>() {{
            put("key", "value");
        }});
        String json = codec.getValueEncoder().encode(taskView).toString(Charset.defaultCharset());
        assertThat(json).contains("\"key\":\"value\"");
        assertThat(json).contains("\"name\":\"name\"");

        TaskView<?> actualTask = (TaskView<?>) codec.getValueDecoder().decode(wrappedBuffer(json.getBytes()), new State());
        assertThat(actualTask.name).isEqualTo("name");
        assertThat(actualTask.properties).hasSize(1);
        assertThat(actualTask.properties).includes(entry("key", "value"));
    }
}
