package org.icij.datashare.tasks;

import com.google.inject.Singleton;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.redisson.api.RedissonClient;

import javax.inject.Inject;
import java.util.concurrent.BlockingQueue;

@Singleton
public class TaskSupplierRedis extends org.icij.datashare.asynctasks.TaskSupplierRedis {
    // Convenience class made to ease injection and test
    @Inject
    public TaskSupplierRedis(RedissonClient redissonClient, BlockingQueue<Task<?>> taskQueue) {
        super(redissonClient, taskQueue);
    }

    TaskSupplierRedis(PropertiesProvider propertiesProvider, BlockingQueue<Task<?>> taskQueue) {
        this(new RedissonClientFactory().withOptions(Options.from(propertiesProvider.getProperties())).create(), taskQueue);
    }

}
