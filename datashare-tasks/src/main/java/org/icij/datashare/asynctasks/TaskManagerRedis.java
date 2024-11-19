package org.icij.datashare.asynctasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import org.icij.datashare.asynctasks.bus.amqp.AmqpQueue;
import org.icij.datashare.asynctasks.bus.amqp.CancelEvent;
import org.icij.datashare.asynctasks.bus.amqp.ShutdownEvent;
import org.icij.datashare.asynctasks.bus.amqp.TaskEvent;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.tasks.RoutingStrategy;
import org.icij.datashare.user.User;
import org.redisson.Redisson;
import org.redisson.RedissonBlockingQueue;
import org.redisson.RedissonMap;
import org.redisson.api.RKeys;
import org.redisson.api.RTopic;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.BaseCodec;
import org.redisson.client.handler.State;
import org.redisson.client.protocol.Decoder;
import org.redisson.client.protocol.Encoder;
import org.redisson.command.CommandSyncService;
import org.redisson.liveobject.core.RedissonObjectBuilder;

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
import java.util.stream.StreamSupport;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

public class TaskManagerRedis implements TaskManager {
    private final Runnable eventCallback; // for test
    public static final String EVENT_CHANNEL_NAME = "EVENT";
    private final RedissonMap<String, Task<?>> tasks;
    private final RTopic eventTopic;
    private final RedissonClient redissonClient;
    private final RoutingStrategy routingStrategy;

    public TaskManagerRedis(RedissonClient redissonClient, String taskMapName) {
        this(redissonClient, taskMapName, RoutingStrategy.UNIQUE, null);
    }

    public TaskManagerRedis(RedissonClient redissonClient, String taskMapName, RoutingStrategy routingStrategy, Runnable eventCallback) {
        this.redissonClient = redissonClient;
        this.routingStrategy = routingStrategy;
        CommandSyncService commandSyncService = getCommandSyncService();
        this.tasks = new RedissonMap<>(new TaskViewCodec(), commandSyncService, taskMapName, redissonClient, null, null);
        this.eventTopic = redissonClient.getTopic(EVENT_CHANNEL_NAME);
        this.eventCallback = eventCallback;
        addEventListener(this::handleEvent);
    }

    @Override
    public <V> Task<V> getTask(String id) {
        return (Task<V>) tasks.get(id);
    }

    @Override
    public List<Task<?>> getTasks() {
        return new LinkedList<>(tasks.values());
    }

    @Override
    public List<Task<?>> getTasks(User user, Pattern pattern) {
        return TaskManager.getTasks(tasks.values().stream(), user, pattern);
    }

    @Override
    public List<Task<?>> clearDoneTasks() {
        return tasks.values().stream().filter(Task::isFinished).map(t -> tasks.remove(t.id)).collect(toList());
    }

    @Override
    public Task<?> clearTask(String taskId) {
        if (tasks.get(taskId).getState() == Task.State.RUNNING) {
            throw new IllegalStateException(String.format("task id <%s> is already in RUNNING state", taskId));
        }
        logger.info("deleting task id <{}>", taskId);
        return tasks.remove(taskId);
    }

    @Override
    public boolean stopTask(String taskId) {
        Task<?> taskView = getTask(taskId);
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
        return eventTopic.publish(new ShutdownEvent()) > 0;
    }

    BlockingQueue<Task<?>> taskQueue(Task<?> task) {
        switch (routingStrategy) {
            case GROUP -> {
                return new RedissonBlockingQueue<>(new TaskViewCodec(), getCommandSyncService(), String.format("%s.%s", AmqpQueue.TASK.name(), task.getGroup().id()), redissonClient);
            }
            case NAME -> {
                return new RedissonBlockingQueue<>(new TaskViewCodec(), getCommandSyncService(), String.format("%s.%s", AmqpQueue.TASK.name(), task.name), redissonClient);
            }
            default -> {
                return new RedissonBlockingQueue<>(new TaskViewCodec(), getCommandSyncService(), AmqpQueue.TASK.name(), redissonClient);
            }
        }
    }

    private CommandSyncService getCommandSyncService() {
        return new CommandSyncService(((Redisson) redissonClient).getConnectionManager(), new RedissonObjectBuilder(redissonClient));
    }

    @Override
    public void close() throws IOException {
        logger.info("closing");
        // we cannot close RedissonClient connection pool as it may be used by other keys
        eventTopic.removeAllListeners();
    }

    @Override
    public void clear() {
        tasks.clear();
        clearTaskQueues();
    }

    private void clearTaskQueues() {
        RKeys keys = redissonClient.getKeys();
        Iterable<String> iterable = keys.getKeysByPattern(AmqpQueue.TASK.name() + "*", 100);
        StreamSupport
                .stream(iterable.spliterator(), false)
                .filter(k -> keys.getType(k) == RType.LIST)
                .forEach(k -> redissonClient.getQueue(k).delete());
    }

    public boolean save(Task<?> task) {
        Task<?> oldVal = tasks.put(task.id, task);
        return oldVal == null;
    }

    @Override
    public void enqueue(Task<?> task) {
        taskQueue(task).add(task);
    }

    public static class TaskViewCodec extends BaseCodec {
        private final Encoder keyEncoder;
        private final Decoder<Object> keyDecoder;
        protected final ObjectMapper mapObjectMapper;

        public TaskViewCodec() {
            this.mapObjectMapper = JsonObjectMapper.MAPPER;

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
                return mapObjectMapper.readValue((InputStream) new ByteBufInputStream(buf), Task.class);
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
