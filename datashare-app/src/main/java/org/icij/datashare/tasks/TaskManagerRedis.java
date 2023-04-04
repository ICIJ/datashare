package org.icij.datashare.tasks;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.jsontype.TypeResolverBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.redisson.Redisson;
import org.redisson.RedissonMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.BaseCodec;
import org.redisson.client.handler.State;
import org.redisson.client.protocol.Decoder;
import org.redisson.client.protocol.Encoder;
import org.redisson.command.CommandSyncService;
import org.redisson.liveobject.core.RedissonObjectBuilder;

import javax.inject.Inject;
import javax.xml.datatype.XMLGregorianCalendar;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static java.util.stream.Collectors.toList;
import static org.elasticsearch.painless.api.Augmentation.asList;

public class TaskManagerRedis implements TaskManager {
    public static final String DS_TASK_MANAGER_QUEUE_NAME = "ds:task:manager";
    private final RedissonMap<String, TaskView<?>> tasks;
    private final BlockingQueue<TaskView<?>> batchDownloadQueue;

    @Inject
    public TaskManagerRedis(RedissonClient redissonClient, BlockingQueue<TaskView<?>> batchDownloadQueue) {
        CommandSyncService commandSyncService = new CommandSyncService(((Redisson) redissonClient).getConnectionManager(), new RedissonObjectBuilder(redissonClient));
        this.tasks = new RedissonMap<>(new TaskViewCodec(), commandSyncService, DS_TASK_MANAGER_QUEUE_NAME, redissonClient, null, null);
        this.batchDownloadQueue = batchDownloadQueue;
    }

    public TaskManagerRedis(PropertiesProvider propertiesProvider, BlockingQueue<TaskView<?>> batchDownloadQueue) {
        this(propertiesProvider, DS_TASK_MANAGER_QUEUE_NAME, batchDownloadQueue);
    }
    
    TaskManagerRedis(PropertiesProvider propertiesProvider, String taskMapName, BlockingQueue<TaskView<?>> batchDownloadQueue) {
        RedissonClient redissonClient = new RedissonClientFactory().withOptions(Options.from(propertiesProvider.getProperties())).create();
        CommandSyncService commandSyncService = new CommandSyncService(((Redisson) redissonClient).getConnectionManager(), new RedissonObjectBuilder(redissonClient));
        this.tasks = new RedissonMap<>(new TaskViewCodec(), commandSyncService, taskMapName, redissonClient, null, null);
        this.batchDownloadQueue = batchDownloadQueue;
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
        return tasks.values().stream().filter(f -> f.getState() != TaskView.State.RUNNING).map(t -> tasks.remove(t.name)).collect(toList());
    }
    @Override
    public TaskView<?> clearTask(String taskName) {
        return tasks.remove(taskName);
    }

    @Override public TaskView<Void> startTask(Runnable task) { throw new IllegalStateException("not implemented"); }
    @Override public <V> TaskView<V> startTask(Callable<V> task, Runnable callback) { throw new IllegalStateException("not implemented"); }
    @Override public <V> TaskView<V> startTask(Callable<V> task, Map<String, Object> properties) {
        MonitorableFutureTask<V> futureTask = new MonitorableFutureTask<>(task, properties);
        TaskView<V> taskView = new TaskView<>(futureTask);
        save(taskView);
        batchDownloadQueue.add(taskView);
        return taskView;
    }
    @Override public <V> TaskView<V> startTask(Callable<V> task) { throw new IllegalStateException("not implemented"); }

    @Override
    public <V> TaskView<V> takeTask() {
        return null;
    }

    @Override public boolean stopTask(String taskName) { throw new IllegalStateException("not implemented"); }
    @Override public boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException { throw new IllegalStateException("not implemented"); }

    static class TaskViewCodec extends BaseCodec {
        private final Encoder keyEncoder;
        private final Decoder<Object> keyDecoder;
        protected final ObjectMapper mapObjectMapper;

        public TaskViewCodec() {
            this.mapObjectMapper = new ObjectMapper();
            this.mapObjectMapper.writerFor(new TypeReference<List<Throwable>>() {});
            init(this.mapObjectMapper);
            initTypeInclusion(this.mapObjectMapper);

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
        protected void init(ObjectMapper objectMapper) {
            objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
            objectMapper.setVisibility(objectMapper.getSerializationConfig()
                    .getDefaultVisibilityChecker()
                    .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                    .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                    .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                    .withCreatorVisibility(JsonAutoDetect.Visibility.NONE));
            objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            objectMapper.enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
            objectMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
            objectMapper.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
            objectMapper.addMixIn(Throwable.class, ThrowableMixIn.class);
        }

        protected void initTypeInclusion(ObjectMapper mapObjectMapper) {
            TypeResolverBuilder<?> mapTyper = new ObjectMapper.DefaultTypeResolverBuilder(ObjectMapper.DefaultTyping.NON_FINAL) {
                public boolean useForType(JavaType t) {
                    switch (_appliesFor) {
                        case NON_CONCRETE_AND_ARRAYS:
                            while (t.isArrayType()) {
                                t = t.getContentType();
                            }
                            // fall through
                        case OBJECT_AND_NON_CONCRETE:
                            return (t.getRawClass() == Object.class) || !t.isConcrete();
                        case NON_FINAL:
                            while (t.isArrayType()) {
                                t = t.getContentType();
                            }
                            // to fix problem with wrong long to int conversion
                            if (t.getRawClass() == Long.class) {
                                return true;
                            }
                            if (t.getRawClass() == XMLGregorianCalendar.class) {
                                return false;
                            }
                            return !t.isFinal(); // includes Object.class
                        default:
                            // case JAVA_LANG_OBJECT:
                            return t.getRawClass() == Object.class;
                    }
                }
            };
            mapTyper.init(JsonTypeInfo.Id.CLASS, null);
            mapTyper.inclusion(JsonTypeInfo.As.PROPERTY);
            mapObjectMapper.setDefaultTyping(mapTyper);
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
                } catch (Exception e) {
                    out.release();
                    throw new IOException(e);
                }
            }
        };

        @JsonIdentityInfo(generator= ObjectIdGenerators.IntSequenceGenerator.class, property="@id")
        @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
                getterVisibility = JsonAutoDetect.Visibility.PUBLIC_ONLY,
                setterVisibility = JsonAutoDetect.Visibility.NONE,
                isGetterVisibility = JsonAutoDetect.Visibility.NONE)
        public static class ThrowableMixIn {}

        private final Decoder<Object> decoder = new Decoder<Object>() {
            @Override
            public Object decode(ByteBuf buf, State state) throws IOException {
                return mapObjectMapper.readValue((InputStream) new ByteBufInputStream(buf), Object.class);
            }
        };

        @Override public Decoder<Object> getValueDecoder() { return decoder; }
        @Override public Encoder getValueEncoder() { return encoder; }
        @Override public Decoder<Object> getMapKeyDecoder() { return keyDecoder; }
        @Override public Encoder getMapKeyEncoder() { return keyEncoder; }
    }
}
