package org.icij.datashare.tasks;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.jsontype.DefaultBaseTypeLimitingValidator;
import com.fasterxml.jackson.databind.jsontype.TypeResolverBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import net.codestory.http.security.User;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.mode.CommonMode;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.jetbrains.annotations.NotNull;
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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.toList;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;

public class TaskManagerRedis implements TaskManager, TaskSupplier {
    private final RedissonMap<String, TaskView<?>> tasks;
    private final BlockingQueue<TaskView<?>> taskQueue;

    @Inject
    public TaskManagerRedis(RedissonClient redissonClient, String taskMapName, BlockingQueue<TaskView<?>> taskQueue) {
        CommandSyncService commandSyncService = new CommandSyncService(((Redisson) redissonClient).getConnectionManager(), new RedissonObjectBuilder(redissonClient));
        this.tasks = new RedissonMap<>(new TaskViewCodec(), commandSyncService, taskMapName, redissonClient, null, null);
        this.taskQueue = taskQueue;
    }

    public TaskManagerRedis(PropertiesProvider propertiesProvider, BlockingQueue<TaskView<?>> batchDownloadQueue) {
        this(propertiesProvider, CommonMode.DS_TASK_MANAGER_QUEUE_NAME, batchDownloadQueue);
    }
    
    TaskManagerRedis(PropertiesProvider propertiesProvider, String taskMapName, BlockingQueue<TaskView<?>> taskQueue) {
        RedissonClient redissonClient = new RedissonClientFactory().withOptions(Options.from(propertiesProvider.getProperties())).create();
        CommandSyncService commandSyncService = new CommandSyncService(((Redisson) redissonClient).getConnectionManager(), new RedissonObjectBuilder(redissonClient));
        this.tasks = new RedissonMap<>(new TaskViewCodec(), commandSyncService, taskMapName, redissonClient, null, null);
        this.taskQueue = taskQueue;
    }

    void save(TaskView<?> task) {
        tasks.put(task.id, task);
    }

    @Override
    public <V> TaskView<V> getTask(String id) {return (TaskView<V>) tasks.get(id);}

    @Override
    public List<TaskView<?>> getTasks() {
        return new LinkedList<>(tasks.values());
    }

    @Override
    public List<TaskView<?>> getTasks(User user, Pattern pattern) {
        return TaskManager.getTasks(tasks.values().stream(), user, pattern);
    }

    @Override
    public <V extends Serializable> TaskView<V> get(int timeOut, TimeUnit timeUnit) throws InterruptedException {
        return (TaskView<V>) taskQueue.poll(60, TimeUnit.SECONDS);
    }

    @Override
    public List<TaskView<?>> clearDoneTasks() {
        return tasks.values().stream().filter(f -> f.getState() != TaskView.State.RUNNING).map(t -> tasks.remove(t.id)).collect(toList());
    }
    @Override
    public TaskView<?> clearTask(String taskName) {
        return tasks.remove(taskName);
    }

    @Override public <V> TaskView<V> startTask(Callable<V> task, Runnable callback) { throw new IllegalStateException("not implemented"); }
    @Override public <V> TaskView<V> startTask(String taskName, Map<String, Object> properties) {
        BatchDownload batchDownload = (BatchDownload) properties.get("batchDownload");
        TaskView<V> taskView = new TaskView<>(taskName, batchDownload.user, properties);
        save(taskView);
        taskQueue.add(taskView);
        return taskView;
    }
    @Override public <V> TaskView<V> startTask(Callable<V> task) { throw new IllegalStateException("not implemented"); }
    @Override public boolean stopTask(String taskName) { throw new IllegalStateException("not implemented"); }

    @Override
    public Map<String, Boolean> stopAllTasks(User user) { throw new IllegalStateException("not implemented"); }

    @Override public boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) {
        taskQueue.add(TaskView.nullObject());
        return true;
    }

    @Override
    public Void progress(String taskId, double rate) {
        TaskView<?> taskView = tasks.get(taskId);
        taskView.setProgress(rate);
        tasks.put(taskId, taskView);
        return null;
    }

    @Override
    public <V extends Serializable> void result(String taskId, V result) {
        TaskView<V> taskView = (TaskView<V>) tasks.get(taskId);
        taskView.setResult(result);
        tasks.put(taskId, taskView);
    }

    @Override
    public void error(String taskId, Throwable reason) {
        TaskView taskView = tasks.get(taskId);
        taskView.setError(reason);
        tasks.put(taskId, taskView);
    }

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
        protected void init(@NotNull ObjectMapper objectMapper) {
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

        protected void initTypeInclusion(@NotNull ObjectMapper mapObjectMapper) {
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

        private final Decoder<Object> decoder = new Decoder<>() {
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
