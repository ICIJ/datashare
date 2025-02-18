package org.icij.datashare.asynctasks;

import io.netty.buffer.Unpooled;
import org.fest.assertions.Assertions;
import org.icij.datashare.asynctasks.bus.amqp.UriResult;
import org.icij.datashare.user.User;
import org.junit.Test;
import org.redisson.client.handler.State;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class TaskManagerRedisCodecTest {
    TaskManagerRedis.TaskViewCodec codec = new TaskManagerRedis.TaskViewCodec();

    @Test
    public void test_json_serialize_deserialize_with_inline_properties_map() throws Exception {
        Task<?> taskView = new Task<>("name", User.local(), new Group(TaskGroupType.Test), Map.of("key", "value"));
        String json = codec.getValueEncoder().encode(taskView).toString(Charset.defaultCharset());

        assertThat(json).contains("\"key\":\"value\"");
        assertThat(json).contains("\"name\":\"name\"");

        Task<?> actualTask = (Task<?>) codec.getValueDecoder().decode(Unpooled.wrappedBuffer(json.getBytes()), new State());
        Assertions.assertThat(actualTask.name).isEqualTo("name");
        Assertions.assertThat(actualTask.args).hasSize(3);
        Assertions.assertThat(actualTask.args).includes(entry("key", "value"));
        Assertions.assertThat(actualTask.getUser()).isEqualTo(User.local());
        Assertions.assertThat(actualTask.getGroup()).isEqualTo(new Group(TaskGroupType.Test));
    }

    @Test
    public void test_uri_result() throws Exception {
        Task<?> task = new Task<>("name", User.local(), new Group(TaskGroupType.Test), new HashMap<>());
        task.setResult(new UriResult(new URI("file://uri"), 123L));

        assertThat(encodeDecode(task).getResult()).isInstanceOf(UriResult.class);
    }

    @Test
    public void test_simple_results() throws Exception {
        Task<?> task = new Task<>("name", User.local(), new Group(TaskGroupType.Test), new HashMap<>());
        task.setResult(123L);
        assertThat(encodeDecode(task).getResult()).isInstanceOf(Long.class);

        task.setResult("string");
        assertThat(encodeDecode(task).getResult()).isInstanceOf(String.class);

        task.setResult(123);
        assertThat(encodeDecode(task).getResult()).isInstanceOf(Integer.class);
    }

    private Task<?> encodeDecode(Task<?> task) throws IOException {
        String json = codec.getValueEncoder().encode(task).toString(Charset.defaultCharset());
        return (Task<?>) codec.getValueDecoder().decode(Unpooled.wrappedBuffer(json.getBytes()), new State());
    }
}
