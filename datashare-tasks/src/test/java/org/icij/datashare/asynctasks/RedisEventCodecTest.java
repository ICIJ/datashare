package org.icij.datashare.asynctasks;

import io.netty.buffer.ByteBuf;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.icij.datashare.asynctasks.bus.amqp.ErrorEvent;
import org.icij.datashare.asynctasks.bus.amqp.Event;
import org.icij.datashare.asynctasks.bus.amqp.ResultEvent;
import org.icij.datashare.asynctasks.bus.amqp.UriResult;
import org.icij.datashare.json.JsonObjectMapper;
import org.junit.BeforeClass;
import org.junit.Test;
import org.redisson.client.codec.BaseCodec;

import static org.fest.assertions.Assertions.assertThat;

public class RedisEventCodecTest {
    private final BaseCodec codec = TaskManagerRedis.EVENT_CODEC;

    @BeforeClass
    public static void registerSubtypes() {
        JsonObjectMapper.registerSubtypes(new NamedType(UriResult.class), new NamedType(Holder.class));
    }

    @Test(expected = IOException.class)
    public void rejects_unregistered_event_type() throws Exception {
        decode("{\"@type\":\"java.net.URL\",\"createdAt\":0,\"retries\":3}");
    }

    @Test
    public void turns_non_allowlisted_class_nested_in_result_value_into_error_event() throws Exception {
        String json = "{\"@type\":\"ResultEvent\",\"taskId\":\"t\",\"result\":{\"value\":"
                + "{\"@type\":\"RedisEventCodecTest$Holder\",\"payload\":[\"java.util.UUID\",\"00000000-0000-0000-0000-000000000000\"]}}}";
        ErrorEvent event = (ErrorEvent) decode(json);
        assertThat(event.taskId).isEqualTo("t");
        assertThat(event.error.getMessage()).contains("denied resolution");
    }

    @Test
    public void round_trips_result_event_with_uri_result() throws Exception {
        ResultEvent<UriResult> sent = new ResultEvent<>("t", new TaskResult<>(new UriResult(URI.create("file:///x"), 3L)));
        ByteBuf encoded = codec.getValueEncoder().encode(sent);
        try {
            Event received = (Event) codec.getValueDecoder().decode(encoded, null);
            assertThat(((ResultEvent<?>) received).result).isEqualTo(sent.result);
        } finally {
            encoded.release();
        }
    }

    private Event decode(String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        try {
            return (Event) codec.getValueDecoder().decode(buf, null);
        } finally {
            buf.release();
        }
    }

    public static class Holder implements Serializable {
        public Object payload;
    }
}
