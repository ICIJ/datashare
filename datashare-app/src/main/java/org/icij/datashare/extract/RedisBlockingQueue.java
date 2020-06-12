package org.icij.datashare.extract;

import com.google.inject.Inject;
import org.icij.datashare.PropertiesProvider;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.redisson.Redisson;
import org.redisson.RedissonBlockingQueue;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.command.CommandSyncService;

import java.io.Closeable;

public class RedisBlockingQueue extends RedissonBlockingQueue<String> implements Closeable {
    private final RedissonClient redissonClient;

    @Inject
    public RedisBlockingQueue(PropertiesProvider propertiesProvider) {
        this(new RedissonClientFactory().withOptions(Options.from(propertiesProvider.getProperties())).create(), "ds:batchsearch:queue");
    }

    public RedisBlockingQueue(RedissonClient redissonClient, String queueName) {
        super(new StringCodec(), new CommandSyncService(((Redisson)redissonClient).getConnectionManager()), queueName, redissonClient);
        this.redissonClient = redissonClient;
    }

    public void close() {
        redissonClient.shutdown();
    }
}
