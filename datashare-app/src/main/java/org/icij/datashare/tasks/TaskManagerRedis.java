package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.mode.CommonMode;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.redisson.api.RedissonClient;

@Singleton
public class TaskManagerRedis extends org.icij.datashare.asynctasks.TaskManagerRedis implements DatashareTaskManager {
    private final int pollingIntervalMs; // for tests

    // Convenience class made to ease injection and test
    @Inject
    public TaskManagerRedis(RedissonClient redissonClient, PropertiesProvider propertiesProvider) {
        this(redissonClient, CommonMode.DS_TASK_MANAGER_MAP_NAME, Utils.getRoutingStrategy(propertiesProvider), null, POLLING_INTERVAL);
    }

    public TaskManagerRedis(PropertiesProvider propertiesProvider) {
        this(propertiesProvider, CommonMode.DS_TASK_MANAGER_MAP_NAME, null, POLLING_INTERVAL);
    }

   TaskManagerRedis(PropertiesProvider propertiesProvider, String taskMapName, Runnable eventCallback, int pollingIntervalMs) {
        this(new RedissonClientFactory().withOptions(Options.from(propertiesProvider.getProperties())).create(), taskMapName, Utils.getRoutingStrategy(propertiesProvider), eventCallback, pollingIntervalMs);
    }

   TaskManagerRedis(RedissonClient redissonClient, String taskMapName, RoutingStrategy routingStrategy, Runnable eventCallback, int pollingIntervalMs) {
        super(redissonClient, taskMapName, routingStrategy, eventCallback);
        this.pollingIntervalMs = pollingIntervalMs;
    }

    @Override
    public int getTerminationPollingInterval() {
        return pollingIntervalMs;
    }
}
