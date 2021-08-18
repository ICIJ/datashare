package org.icij.datashare.tasks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.redisson.Redisson;
import org.redisson.RedissonMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.protocol.Decoder;
import org.redisson.client.protocol.Encoder;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.command.CommandSyncService;
import org.redisson.liveobject.core.RedissonObjectBuilder;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static java.util.stream.Collectors.toList;
import static org.elasticsearch.painless.api.Augmentation.asList;

public class TaskManagerRedis implements TaskManager {
    private final RedissonMap<String, TaskView<?>> tasks;

    public TaskManagerRedis(PropertiesProvider propertiesProvider) {
        RedissonClient redissonClient = new RedissonClientFactory().withOptions(Options.from(propertiesProvider.getProperties())).create();
        CommandSyncService commandSyncService = new CommandSyncService(((Redisson) redissonClient).getConnectionManager(), new RedissonObjectBuilder(redissonClient));
        this.tasks = new RedissonMap<>(new TaskViewCodec(), commandSyncService, "ds:task:manager", redissonClient, null, null);
    }

    @Override
    public <V> Void save(TaskView<V> task) {
        tasks.put(task.name, task);
        return null;
    }

    @Override
    public TaskView<?> get(String id) {
        return tasks.get(id);
    }

    @Override
    public List<TaskView<?>> get() {
        return asList(tasks.values());
    }

    @Override
    public List<TaskView<?>> clearDoneTasks() {
        return tasks.values().stream().filter(f -> f.state != TaskView.State.RUNNING).map(t -> tasks.remove(t.toString())).collect(toList());
    }

    @Override public MonitorableFutureTask<Void> startTask(Runnable task) { throw new IllegalStateException("not implemented"); }
    @Override public <V> MonitorableFutureTask<V> startTask(Callable<V> task, Runnable callback) { throw new IllegalStateException("not implemented"); }
    @Override public <V> MonitorableFutureTask<V> startTask(Callable<V> task, Map<String, Object> properties) { throw new IllegalStateException("not implemented"); }
    @Override public <V> MonitorableFutureTask<V> startTask(Callable<V> task) { throw new IllegalStateException("not implemented"); }
    @Override public boolean stopTask(String taskName) { throw new IllegalStateException("not implemented"); }
    @Override public boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException { throw new IllegalStateException("not implemented"); }

    static class TaskViewCodec extends JsonJacksonCodec {
        private final Encoder keyEncoder;
        private final Decoder<Object> keyDecoder;

        public TaskViewCodec() {
            super(JsonObjectMapper.MAPPER);
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
        @Override public Decoder<Object> getMapKeyDecoder() { return keyDecoder; }
        @Override public Encoder getMapKeyEncoder() { return keyEncoder; }
    }
}
