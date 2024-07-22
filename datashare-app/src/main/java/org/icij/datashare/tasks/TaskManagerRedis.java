package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.mode.CommonMode;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.redisson.api.RedissonClient;

import java.util.concurrent.BlockingQueue;

@Singleton
public class TaskManagerRedis extends org.icij.datashare.asynctasks.TaskManagerRedis {
    // Convenience class made to ease injection and test
    @Inject
    public TaskManagerRedis(RedissonClient redissonClient, BlockingQueue<Task<?>> taskQueue) {
        this(redissonClient, taskQueue, CommonMode.DS_TASK_MANAGER_MAP_NAME, null);
    }

    public TaskManagerRedis(PropertiesProvider propertiesProvider, BlockingQueue<Task<?>> taskQueue) {
        this(propertiesProvider, CommonMode.DS_TASK_MANAGER_MAP_NAME, taskQueue, null);
    }

    TaskManagerRedis(PropertiesProvider propertiesProvider, String taskMapName, BlockingQueue<Task<?>> taskQueue, Runnable eventCallback) {
        this(new RedissonClientFactory().withOptions(Options.from(propertiesProvider.getProperties())).create(), taskQueue, taskMapName, eventCallback);
    }

    TaskManagerRedis(RedissonClient redissonClient, BlockingQueue<Task<?>> taskQueue, String taskMapName, Runnable eventCallback) {
        super(redissonClient, taskQueue, taskMapName, eventCallback);
    }
}
