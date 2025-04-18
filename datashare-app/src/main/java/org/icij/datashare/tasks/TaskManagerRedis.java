package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.TaskRepository;
import org.icij.datashare.asynctasks.TaskRepositoryRedis;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.redisson.api.RedissonClient;

import static org.icij.datashare.cli.DatashareCliOptions.TASK_MANAGER_POLLING_INTERVAL_OPT;

@Singleton
public class TaskManagerRedis extends org.icij.datashare.asynctasks.TaskManagerRedis {

    // Convenience class made to ease injection and test
    @Inject
    public TaskManagerRedis(RedissonClient redissonClient, PropertiesProvider propertiesProvider, TaskRepository taskRepository) {
        this(redissonClient, taskRepository, Utils.getRoutingStrategy(propertiesProvider), null,
                Integer.parseInt(propertiesProvider.get(TASK_MANAGER_POLLING_INTERVAL_OPT).orElse(String.valueOf(DEFAULT_TASK_POLLING_INTERVAL_MS))));
    }

    TaskManagerRedis(PropertiesProvider propertiesProvider, String taskMapName, Runnable eventCallback, int pollingIntervalMs) {
        this(new RedissonClientFactory().withOptions(Options.from(propertiesProvider.getProperties())).create(), taskMapName,
                Utils.getRoutingStrategy(propertiesProvider), eventCallback,
                Integer.parseInt(propertiesProvider.get(TASK_MANAGER_POLLING_INTERVAL_OPT).orElse(String.valueOf(DEFAULT_TASK_POLLING_INTERVAL_MS))));
    }

    TaskManagerRedis(RedissonClient redissonClient, TaskRepository tasks, RoutingStrategy routingStrategy, Runnable eventCallback, int taskPollingIntervalMs) {
        super(redissonClient, tasks, routingStrategy, eventCallback, taskPollingIntervalMs);
    }

    TaskManagerRedis(RedissonClient redissonClient, String taskMapName, RoutingStrategy routingStrategy, Runnable eventCallback, int taskPollingIntervalMs) {
        super(redissonClient, new TaskRepositoryRedis(redissonClient, taskMapName), routingStrategy, eventCallback, taskPollingIntervalMs);
    }
}
