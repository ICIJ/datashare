package org.icij.datashare.asynctasks.bus.redis;

import java.io.Closeable;
import java.util.concurrent.TimeUnit;
import org.redisson.Redisson;
import org.redisson.RedissonBlockingQueue;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.command.CommandSyncService;
import org.redisson.liveobject.core.RedissonObjectBuilder;

public class RedisBlockingQueue<T> extends RedissonBlockingQueue<T> implements Closeable {
    private final RedissonClient redissonClient;

    public RedisBlockingQueue(RedissonClient redissonClient, String queueName) {
        super(new JsonJacksonCodec(),
            new CommandSyncService(((Redisson) redissonClient).getConnectionManager(),
                new RedissonObjectBuilder(redissonClient)), queueName, redissonClient);
        this.redissonClient = redissonClient;
    }

    public void close() {
        redissonClient.shutdown(0, 2, TimeUnit.SECONDS);
    }
}
