package org.icij.datashare.tasks;

import com.google.inject.Singleton;
import org.icij.datashare.PropertiesProvider;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.redisson.api.RedissonClient;

import javax.inject.Inject;

@Singleton
public class TaskSupplierRedis extends org.icij.datashare.asynctasks.TaskSupplierRedis {
    // Convenience class made to ease injection and test
    @Inject
    public TaskSupplierRedis(RedissonClient redissonClient, PropertiesProvider propertiesProvider) {
        super(redissonClient, Utils.getRoutingKey(propertiesProvider));
    }

    TaskSupplierRedis(PropertiesProvider propertiesProvider) {
        this(new RedissonClientFactory().withOptions(Options.from(propertiesProvider.getProperties())).create(), propertiesProvider);
    }
}
