package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCliOptions;
import org.icij.datashare.mode.CommonMode;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RedissonClient;

@Singleton
public class TaskManagerRedis extends org.icij.datashare.asynctasks.TaskManagerRedis {

    // Convenience class made to ease injection and test
    @Inject
    public TaskManagerRedis(RedissonClient redissonClient, PropertiesProvider propertiesProvider) {
        this(redissonClient, CommonMode.DS_TASK_MANAGER_MAP_NAME, getRoutingStrategy(propertiesProvider), null);
    }

    public TaskManagerRedis(PropertiesProvider propertiesProvider) {
        this(propertiesProvider, CommonMode.DS_TASK_MANAGER_MAP_NAME, null);
    }

    TaskManagerRedis(PropertiesProvider propertiesProvider, String taskMapName, Runnable eventCallback) {
        this(new RedissonClientFactory().withOptions(Options.from(propertiesProvider.getProperties())).create(), taskMapName, getRoutingStrategy(propertiesProvider), eventCallback);
    }

    TaskManagerRedis(RedissonClient redissonClient, String taskMapName, RoutingStrategy routingStrategy, Runnable eventCallback) {
        super(redissonClient, taskMapName, routingStrategy, eventCallback);
    }

    @NotNull
    private static RoutingStrategy getRoutingStrategy(PropertiesProvider propertiesProvider) {
        return RoutingStrategy.valueOf(propertiesProvider.get(DatashareCliOptions.TASK_ROUTING_STRATEGY_OPT).orElse(DatashareCliOptions.DEFAULT_TASK_ROUTING_STRATEGY.name()));
    }
}
