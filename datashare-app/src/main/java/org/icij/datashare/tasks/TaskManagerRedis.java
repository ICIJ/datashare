package org.icij.datashare.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.bus.amqp.CancelEvent;
import org.icij.datashare.com.bus.amqp.Event;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.redisson.Redisson;
import org.redisson.RedissonBlockingQueue;
import org.redisson.RedissonMap;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.BaseCodec;
import org.redisson.client.handler.State;
import org.redisson.client.protocol.Decoder;
import org.redisson.client.protocol.Encoder;
import org.redisson.command.CommandSyncService;
import org.redisson.liveobject.core.RedissonObjectBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
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
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.toList;

@Singleton
public class TaskManagerRedis implements TaskManager, TaskSupplier {
    private Logger logger = LoggerFactory.getLogger(getClass());
    public static final String EVENT_CHANNEL_NAME = "EVENT";
    public static final String LOCK_NAME = "ds:task:manager:lock";
    private final RedissonMap<String, TaskView<?>> tasks;
    private final BlockingQueue<TaskView<?>> taskQueue;
    private final RTopic eventTopic;
    private final RedissonClient redisson;

    @Inject
    public TaskManagerRedis(RedissonClient redissonClient, BlockingQueue<TaskView<?>> taskQueue) {
        this(redissonClient, taskQueue, CommonMode.DS_TASK_MANAGER_MAP_NAME);
    }

    public TaskManagerRedis(PropertiesProvider propertiesProvider, BlockingQueue<TaskView<?>> taskQueue) {
        this(propertiesProvider, CommonMode.DS_TASK_MANAGER_MAP_NAME, taskQueue);
    }
    
    TaskManagerRedis(PropertiesProvider propertiesProvider, String taskMapName, BlockingQueue<TaskView<?>> taskQueue) {
        this(new RedissonClientFactory().withOptions(Options.from(propertiesProvider.getProperties())).create(), taskQueue, taskMapName);
    }

    TaskManagerRedis(RedissonClient redissonClient, BlockingQueue<TaskView<?>> taskQueue, String taskMapName) {
        CommandSyncService commandSyncService = new CommandSyncService(((Redisson) redissonClient).getConnectionManager(), new RedissonObjectBuilder(redissonClient));
        this.tasks = new RedissonMap<>(new TaskViewCodec(), commandSyncService, taskMapName, redissonClient, null, null);
        this.taskQueue = taskQueue;
        this.eventTopic = redissonClient.getTopic(EVENT_CHANNEL_NAME);
        this.redisson = redissonClient;
    }

    @Override
    public <V> TaskView<V> getTask(String id) {return withLock(() ->(TaskView<V>) tasks.get(id), false);}

    @Override
    public List<TaskView<?>> getTasks() {
        return withLock(() -> new LinkedList<>(tasks.values()), false);
    }

    @Override
    public List<TaskView<?>> getTasks(User user, Pattern pattern) {
        return withLock(() ->TaskManager.getTasks(tasks.values().stream(), user, pattern), false);
    }

    @Override
    public <V extends Serializable> TaskView<V> get(int timeOut, TimeUnit timeUnit) throws InterruptedException {
        return (TaskView<V>) taskQueue.poll(timeOut, timeUnit);
    }

    @Override
    public List<TaskView<?>> clearDoneTasks() {
        return tasks.values().stream().filter(TaskView::isFinished).map(t -> tasks.remove(t.id)).collect(toList());
    }
    @Override
    public TaskView<?> clearTask(String taskName) {
        return withLock(() -> tasks.remove(taskName), false);
    }

    @Override public <V> TaskView<V> startTask(String taskName, User user, Map<String, Object> properties) throws IOException  {
        return startTask(new TaskView<>(taskName, user, properties));
    }

    @Override
    public <V> TaskView<V> startTask(String id, String taskName, User user) throws IOException {
        return startTask(new TaskView<>(id, taskName, user, new HashMap<>()));
    }

    private  <V> TaskView<V> startTask(TaskView<V> taskView) throws IOException {
        return withLock(() -> {
            taskView.queue();
            save(taskView);
            taskQueue.add(taskView);
            return taskView;
        }, true);
    }

    @Override public boolean stopTask(String taskId) {
        return eventTopic.publish(new CancelEvent(taskId, false)) > 0;
    }

    @Override
    public Map<String, Boolean> stopAllTasks(User user) { throw new IllegalStateException("not implemented"); }

    @Override public boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) {
        taskQueue.add(TaskView.nullObject());
        return true;
    }

    @Override
    public Void progress(String taskId, double rate) {
        withLock(() -> {
            TaskView<?> taskView = tasks.get(taskId);
            taskView.setProgress(rate);
            tasks.put(taskId, taskView);
        }, true);
        return null;
    }

    @Override
    public <V extends Serializable> void result(String taskId, V result) {
        withLock(() -> {
            TaskView<V> taskView = (TaskView<V>) tasks.get(taskId);
            if (taskView != null) {
                taskView.setResult(result);
                tasks.put(taskId, taskView);
            } else {
                logger.warn("cannot find taskview {} in {}", taskId, tasks);
            }
        }, true);
    }

    @Override
    public void cancel(TaskView<?> task, boolean requeue) {
        withLock(() -> {
            TaskView<?> taskView = tasks.get(task.id);
            taskView.cancel();
            tasks.put(task.id, taskView);
        }, true);
    }

    @Override
    public void error(String taskId, Throwable reason) {
        withLock(() -> {
            TaskView taskView = tasks.get(taskId);
            taskView.setError(reason);
            tasks.put(taskId, taskView);
        }, true);
    }

    @Override
    public void addEventListener(Consumer<Event> callback) {
        eventTopic.addListener(Event.class, (channelString, message) -> callback.accept(message));
    }

    @Override
    public void close() throws IOException {
        withLock(() -> {
            logger.info("closing");
            // we cannot close RedissonClient connection pool as it may be used by other keys
            eventTopic.removeAllListeners();
            tasks.delete();
            if (taskQueue instanceof RedissonBlockingQueue) {
                ((RedissonBlockingQueue<TaskView<?>>) taskQueue).delete();
            }
        }, true);
    }

    public void clear() {
        withLock(() -> {
            tasks.clear();
            taskQueue.clear();
        }, true);
    }

    void save(TaskView<?> task) {
        tasks.put(task.id, task);
    }

    private <V> V withLock(Callable<V> runnable, boolean write) {
        RReadWriteLock rwlock = redisson.getReadWriteLock(LOCK_NAME);
        RLock lock = write? rwlock.writeLock(): rwlock.readLock();
        try {
            if (lock.tryLock(10, 10, TimeUnit.SECONDS)) {
                return runnable.call();
            } else {
                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    private void withLock(Runnable runnable, boolean write) {
        RReadWriteLock rwlock = redisson.getReadWriteLock(LOCK_NAME);
        RLock lock = write? rwlock.writeLock(): rwlock.readLock();
        try {
            if (lock.tryLock(10, 10, TimeUnit.SECONDS)) {
                runnable.run();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
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

        @Override public Decoder<Object> getValueDecoder() { return decoder; }
        @Override public Encoder getValueEncoder() { return encoder; }
        @Override public Decoder<Object> getMapKeyDecoder() { return keyDecoder; }
        @Override public Encoder getMapKeyEncoder() { return keyEncoder; }
    }
}
