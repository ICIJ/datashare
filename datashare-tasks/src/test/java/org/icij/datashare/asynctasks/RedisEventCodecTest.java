package org.icij.datashare.asynctasks;

import io.netty.buffer.ByteBuf;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import io.netty.buffer.ByteBufAllocator;
import java.io.IOException;
import java.net.URI;
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
        JsonObjectMapper.registerSubtypes(new NamedType(UriResult.class));
    }

    @Test(expected = IOException.class)
    public void rejects_unregistered_event_type() throws Exception {
        decode("{\"@type\":\"java.net.URL\",\"createdAt\":0,\"retries\":3}");
    }

    @Test
    public void round_trips_result_event_with_uri_result() throws Exception {
        ResultEvent<UriResult> sent = new ResultEvent<>("t", new TaskResult<>(new UriResult(URI.create("file:///x"), 3L)));
        ByteBuf encoded = codec.getValueEncoder().encode(sent);
        Event received = (Event) codec.getValueDecoder().decode(encoded, null);
        assertThat(((ResultEvent<?>) received).result).isEqualTo(sent.result);
    }

    private Event decode(String json) throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer();
        buf.writeBytes(json.getBytes());
        return (Event) codec.getValueDecoder().decode(buf, null);
    }
}
