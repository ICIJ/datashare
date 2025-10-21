package org.icij.datashare.extract;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.redisson.Redisson;
import org.redisson.RedissonBlockingQueue;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.command.CommandSyncService;
import org.redisson.liveobject.core.RedissonObjectBuilder;

import java.io.Closeable;
import java.util.concurrent.TimeUnit;

public class RedisBlockingQueue<T> extends RedissonBlockingQueue<T> implements Closeable {
    private final RedissonClient redissonClient;

    @Inject
    public RedisBlockingQueue(PropertiesProvider propertiesProvider, @Assisted String queueName) {
        this(new RedissonClientFactory().withOptions(Options.from(propertiesProvider.getProperties())).create(), queueName);
    }

    public RedisBlockingQueue(RedissonClient redissonClient, String queueName) {
        super(new JsonJacksonCodec(), new CommandSyncService(((Redisson)redissonClient).getConnectionManager(), new RedissonObjectBuilder(redissonClient)), queueName, redissonClient);
        this.redissonClient = redissonClient;
    }

    public void close() {
        redissonClient.shutdown(0, 2, TimeUnit.SECONDS);
    }
}
