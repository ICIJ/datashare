package org.icij.datashare.com.queue;

import java.io.Closeable;
import java.util.concurrent.TimeUnit;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.redisson.Redisson;
import org.redisson.RedissonBlockingQueue;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.BaseCodec;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.command.CommandSyncService;
import org.redisson.liveobject.core.RedissonObjectBuilder;

@Singleton
public class RedisBlockingQueue<T> extends RedissonBlockingQueue<T> implements Closeable {
    private final RedissonClient redissonClient;

    @Inject
    public RedisBlockingQueue(RedissonClient redissonClient, String queueName) {
       this(redissonClient, queueName, new JsonJacksonCodec());
    }

    public RedisBlockingQueue(RedissonClient redissonClient, String queueName, BaseCodec codec) {
        super(codec,
                new CommandSyncService(((Redisson) redissonClient).getConnectionManager(),
                        new RedissonObjectBuilder(redissonClient)), queueName, redissonClient);
        this.redissonClient = redissonClient;
    }

    public void close() {
        redissonClient.shutdown(0, 2, TimeUnit.SECONDS);
    }
}
