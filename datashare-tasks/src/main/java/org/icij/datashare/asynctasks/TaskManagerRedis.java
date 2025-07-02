package org.icij.datashare.asynctasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import java.util.List;
import org.icij.datashare.asynctasks.bus.amqp.AmqpQueue;
import org.icij.datashare.asynctasks.bus.amqp.CancelEvent;
import org.icij.datashare.asynctasks.bus.amqp.ShutdownEvent;
import org.icij.datashare.asynctasks.bus.amqp.TaskEvent;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.tasks.RoutingStrategy;
import org.redisson.Redisson;
import org.redisson.RedissonBlockingQueue;
import org.redisson.api.RKeys;
import org.redisson.api.RTopic;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.BaseCodec;
import org.redisson.client.handler.State;
import org.redisson.client.protocol.Decoder;
import org.redisson.client.protocol.Encoder;
import org.redisson.command.CommandSyncService;
import org.redisson.liveobject.core.RedissonObjectBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.asynctasks.Task.State.FINAL_STATES;

public class TaskManagerRedis implements TaskManager {
    private final Runnable eventCallback; // for test
    public static final String EVENT_CHANNEL_NAME = "EVENT";
    protected static final int DEFAULT_TASK_POLLING_INTERVAL_MS = 5000;
    private final TaskRepository tasks;
    private final RTopic eventTopic;
    private final RedissonClient redissonClient;
    private final RoutingStrategy routingStrategy;
    private final int taskPollingIntervalMs;

    public TaskManagerRedis(RedissonClient redissonClient, TaskRepository tasks) {
        this(redissonClient, tasks, RoutingStrategy.UNIQUE, null);
    }

    public TaskManagerRedis(RedissonClient redissonClient, TaskRepository tasks, RoutingStrategy routingStrategy, Runnable eventCallback) {
        this(redissonClient, tasks, routingStrategy, eventCallback, DEFAULT_TASK_POLLING_INTERVAL_MS);
    }

    public TaskManagerRedis(RedissonClient redissonClient, TaskRepository tasks, RoutingStrategy routingStrategy, Runnable eventCallback, int taskPollingIntervalMs) {
        this.redissonClient = redissonClient;
        this.routingStrategy = routingStrategy;
        this.tasks = tasks;
        this.eventTopic = redissonClient.getTopic(EVENT_CHANNEL_NAME);
        this.eventCallback = eventCallback;
        this.taskPollingIntervalMs = taskPollingIntervalMs;
        eventTopic.addListener(TaskEvent.class, (channelString, message) -> handleEvent(message));
    }

    public <V extends Serializable> Task<V> getTask(final String taskId) throws UnknownTask, IOException {
        return tasks.getTask(taskId);
    }

    @Override
    public Stream<Task<?>> getTasks(TaskFilters filters) throws IOException {
        return tasks.getTasks(filters);
    }

    @Override
    public Group getTaskGroup(String taskId) throws IOException {
        return tasks.getTaskGroup(taskId);
    }

    @Override
    public List<Task<?>> clearDoneTasks(TaskFilters filters) throws IOException {
        // Require tasks to be in final state and apply user filters
        Stream<Task<?>> taskStream = tasks.getTasks(filters.withStates(FINAL_STATES))
            .map(t -> {
                try {
                    return tasks.delete(t.id);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        return taskStream.toList();
    }

    @Override
    public <V extends Serializable> Task<V> clearTask(String taskId) throws UnknownTask, IOException {
        if (getTask(taskId).getState() == Task.State.RUNNING) {
            throw new IllegalStateException(String.format("task id <%s> is already in RUNNING state", taskId));
        }
        logger.info("deleting task id <{}>", taskId);
        return tasks.delete(taskId);
    }

    @Override
    public boolean stopTask(String taskId) throws UnknownTask, IOException {
        Task<?> taskView = getTask(taskId);
        if (taskView != null) {
            logger.info("sending cancel event for {}", taskId);
            return eventTopic.publish(new CancelEvent(taskId, false)) > 0;
        } else {
            logger.warn("unknown task id <{}> for cancel call", taskId);
        }
        return false;
    }

    public void handleEvent(TaskEvent e) {
        ofNullable(TaskManager.super.handleAck(e)).flatMap(t -> ofNullable(eventCallback)).ifPresent(Runnable::run);
    }

    @Override
    public boolean shutdown() throws IOException {
        return eventTopic.publish(new ShutdownEvent()) > 0;
    }

    BlockingQueue<Task<?>> taskQueue(Task<?> task) throws IOException {
        switch (routingStrategy) {
            case GROUP -> {
                return new RedissonBlockingQueue<>(new RedisCodec<>(Task.class), getCommandSyncService(), String.format("%s.%s", AmqpQueue.TASK.name(), tasks.getTaskGroup(task.id).id()), redissonClient);
            }
            case NAME -> {
                return new RedissonBlockingQueue<>(new RedisCodec<>(Task.class), getCommandSyncService(), String.format("%s.%s", AmqpQueue.TASK.name(), task.name), redissonClient);
            }
            default -> {
                return new RedissonBlockingQueue<>(new RedisCodec<>(Task.class), getCommandSyncService(), AmqpQueue.TASK.name(), redissonClient);
            }
        }
    }

    private CommandSyncService getCommandSyncService() {
        return new CommandSyncService(((Redisson) redissonClient).getConnectionManager(), new RedissonObjectBuilder(redissonClient));
    }

    @Override
    public int getTerminationPollingInterval() {
        return taskPollingIntervalMs;
    }

    @Override
    public void close() throws IOException {
        logger.info("closing");
        // we cannot close RedissonClient connection pool as it may be used by other keys
        eventTopic.removeAllListeners();
    }

    @Override
    public void clear() throws IOException {
        tasks.deleteAll();
        clearTaskQueues();
    }

    @Override
    public boolean getHealth() {
        try {
            redissonClient.getKeys().count();
            return true;
        } catch (RedisException ex) {
            logger.error("TaskManager Redis Health error : ", ex);
            return false;
        }
    }

    @Override
    public <V extends Serializable> void insert(Task<V> task, Group group) throws TaskAlreadyExists, IOException {
        tasks.insert(task, group);
    }

    @Override
    public <V extends Serializable> void update(Task<V> task) throws IOException, UnknownTask {
        tasks.update(task);
    }

    private void clearTaskQueues() {
        RKeys keys = redissonClient.getKeys();
        Iterable<String> iterable = keys.getKeysByPattern(AmqpQueue.TASK.name() + "*", 100);
        StreamSupport
                .stream(iterable.spliterator(), false)
                .filter(k -> keys.getType(k) == RType.LIST)
                .forEach(k -> redissonClient.getQueue(k).delete());
    }

    @Override
    public <V extends Serializable> void enqueue(Task<V> task) throws IOException {
        taskQueue(task).add(task);
    }

    public  static class RedisCodec<T> extends BaseCodec {
        private final Class<T> clazz;
        private final Encoder keyEncoder;
        private final Decoder<Object> keyDecoder;
        protected final ObjectMapper mapObjectMapper;

        public RedisCodec(Class<T> clazz) {
            this(clazz, JsonObjectMapper.MAPPER);
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
}
