package org.icij.datashare.asynctasks;

import io.netty.buffer.Unpooled;
import org.fest.assertions.Assertions;
import org.icij.datashare.user.User;
import org.junit.Test;
import org.redisson.client.handler.State;

import java.nio.charset.Charset;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class TaskManagerRedisCodecTest {
    TaskManagerRedis.RedisCodec<?> codec = new TaskManagerRedis.RedisCodec<>(Task.class);

    @Test
    public void test_json_serialize_deserialize_with_inline_properties_map() throws Exception {
        Task taskView = new Task("name", User.local(), Map.of("key", "value"));
        String json = codec.getValueEncoder().encode(taskView).toString(Charset.defaultCharset());

        assertThat(json).contains("\"key\":\"value\"");
        assertThat(json).contains("\"name\":\"name\"");

        Task actualTask = (Task) codec.getValueDecoder().decode(Unpooled.wrappedBuffer(json.getBytes()), new State());
        Assertions.assertThat(actualTask.name).isEqualTo("name");
        Assertions.assertThat(actualTask.args).hasSize(2);
        Assertions.assertThat(actualTask.args).includes(entry("key", "value"));
        Assertions.assertThat(actualTask.getUser()).isEqualTo(User.local());
    }

}
