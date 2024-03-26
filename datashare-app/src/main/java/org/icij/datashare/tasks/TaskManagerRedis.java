package org.icij.datashare.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.bus.amqp.CancelEvent;
import org.icij.datashare.com.bus.amqp.TaskEvent;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.redisson.Redisson;
import org.redisson.RedissonBlockingQueue;
import org.redisson.RedissonMap;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.BaseCodec;
import org.redisson.client.handler.State;
import org.redisson.client.protocol.Decoder;
import org.redisson.client.protocol.Encoder;
import org.redisson.command.CommandSyncService;
import org.redisson.liveobject.core.RedissonObjectBuilder;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

@Singleton
public class TaskManagerRedis implements TaskManager {
    private final Runnable eventCallback; // for test
    public static final String EVENT_CHANNEL_NAME = "EVENT";
    private final RedissonMap<String, TaskView<?>> tasks;
    private final BlockingQueue<TaskView<?>> taskQueue;
    private final RTopic eventTopic;

    @Inject
    public TaskManagerRedis(RedissonClient redissonClient, BlockingQueue<TaskView<?>> taskQueue) {
        this(redissonClient, taskQueue, CommonMode.DS_TASK_MANAGER_MAP_NAME, null);
    }

    public TaskManagerRedis(PropertiesProvider propertiesProvider, BlockingQueue<TaskView<?>> taskQueue) {
        this(propertiesProvider, CommonMode.DS_TASK_MANAGER_MAP_NAME, taskQueue, null);
    }

    TaskManagerRedis(PropertiesProvider propertiesProvider, String taskMapName, BlockingQueue<TaskView<?>> taskQueue, Runnable eventCallback) {
        this(new RedissonClientFactory().withOptions(Options.from(propertiesProvider.getProperties())).create(), taskQueue, taskMapName, eventCallback);
    }

    TaskManagerRedis(RedissonClient redissonClient, BlockingQueue<TaskView<?>> taskQueue, String taskMapName, Runnable eventCallback) {
        CommandSyncService commandSyncService = new CommandSyncService(((Redisson) redissonClient).getConnectionManager(), new RedissonObjectBuilder(redissonClient));
        this.tasks = new RedissonMap<>(new TaskViewCodec(), commandSyncService, taskMapName, redissonClient, null, null);
        this.taskQueue = taskQueue;
        this.eventTopic = redissonClient.getTopic(EVENT_CHANNEL_NAME);
        this.eventCallback = eventCallback;
        addEventListener(this::handleEvent);
    }

    @Override
    public <V> TaskView<V> getTask(String id) {
        return (TaskView<V>) tasks.get(id);
    }

    @Override
    public List<TaskView<?>> getTasks() {
        return new LinkedList<>(tasks.values());
    }

    @Override
    public List<TaskView<?>> getTasks(User user, Pattern pattern) {
        return TaskManager.getTasks(tasks.values().stream(), user, pattern);
    }

    @Override
    public List<TaskView<?>> clearDoneTasks() {
        return tasks.values().stream().filter(TaskView::isFinished).map(t -> tasks.remove(t.id)).collect(toList());
    }

    @Override
    public TaskView<?> clearTask(String taskName) {
        return tasks.remove(taskName);
    }

    @Override
    public boolean stopTask(String taskId) {
        TaskView<?> taskView = getTask(taskId);
        if (taskView != null) {
            return eventTopic.publish(new CancelEvent(taskId, false)) > 0;
        } else {
            logger.warn("unknown task id <{}> for cancel call", taskId);
        }
        return false;
    }

    public void handleEvent(TaskEvent e) {
        ofNullable(TaskManager.super.handleAck(e)).ifPresent(t -> ofNullable(eventCallback).ifPresent(Runnable::run));
    }

    public void addEventListener(Consumer<TaskEvent> callback) {
        eventTopic.addListener(TaskEvent.class, (channelString, message) -> callback.accept(message));
    }

    @Override
    public boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) {
        taskQueue.add(TaskView.nullObject());
        return true;
    }

    @Override
    public void close() throws IOException {
        logger.info("closing");
        // we cannot close RedissonClient connection pool as it may be used by other keys
        eventTopic.removeAllListeners();
        tasks.delete();
        if (taskQueue instanceof RedissonBlockingQueue) {
            ((RedissonBlockingQueue<TaskView<?>>) taskQueue).delete();
        }
    }

    @Override
    public void clear() {
        tasks.clear();
        taskQueue.clear();
    }

    public void save(TaskView<?> task) {
        tasks.put(task.id, task);
    }

    @Override
    public void enqueue(TaskView<?> task) {
        taskQueue.add(task);
    }

    static class TaskViewCodec extends BaseCodec {
        private final Encoder keyEncoder;
        private final Decoder<Object> keyDecoder;
        protected final ObjectMapper mapObjectMapper;

        public TaskViewCodec() {
            this.mapObjectMapper = JsonObjectMapper.createTypeInclusionMapper();

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
                } catch (Exception e) {
                    out.release();
                    throw new IOException(e);
                }
            }
        };

        private final Decoder<Object> decoder = new Decoder<>() {
            @Override
            public Object decode(ByteBuf buf, State state) throws IOException {
                return mapObjectMapper.readValue((InputStream) new ByteBufInputStream(buf), Object.class);
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
}
