package org.icij.datashare.asynctasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import org.icij.datashare.json.JsonObjectMapper;
import org.redisson.client.codec.BaseCodec;
import org.redisson.client.handler.State;
import org.redisson.client.protocol.Decoder;
import org.redisson.client.protocol.Encoder;

public class RedisCodec<T> extends BaseCodec {
    private final Class<T> clazz;
    private final Encoder keyEncoder;
    private final Decoder<Object> keyDecoder;
    protected final ObjectMapper mapObjectMapper;

    public RedisCodec(Class<T> clazz) {
        this(clazz, JsonObjectMapper.getMapper());
    }

    public RedisCodec(Class<T> clazz, ObjectMapper objectMapper) {
        // Ugly but this doesn't work with type ref directly
        this.clazz = clazz;
        this.mapObjectMapper = objectMapper;
        this.keyEncoder = in -> {
            ByteBuf out = ByteBufAllocator.DEFAULT.buffer();
            out.writeCharSequence(in.toString(), Charset.defaultCharset());
            return out;
        };
        this.keyDecoder = (buf, state) -> {
            String str = buf.toString(Charset.defaultCharset());
            buf.readerIndex(buf.readableBytes());
            return str;
        };
    }

    private final Encoder encoder = new Encoder() {
        @Override
        public ByteBuf encode(Object in) throws IOException {
            ByteBuf out = ByteBufAllocator.DEFAULT.buffer();
            try {
                ByteBufOutputStream os = new ByteBufOutputStream(out);
                mapObjectMapper.writeValue((OutputStream) os, in);
                return os.buffer();
            } catch (IOException e) {
                out.release();
                throw e;
            }
        }
    };
    private final Decoder<Object> decoder = new Decoder<>() {
        @Override
        public T decode(ByteBuf buf, State state) throws IOException {
            return mapObjectMapper.readValue((InputStream) new ByteBufInputStream(buf), clazz);
        }
    };

    @Override
    public Decoder<Object> getValueDecoder() {
        return decoder;
    }

    @Override
    public Encoder getValueEncoder() {
        return encoder;
    }

    @Override
    public Decoder<Object> getMapKeyDecoder() {
        return keyDecoder;
    }

    @Override
    public Encoder getMapKeyEncoder() {
        return keyEncoder;
    }
}
