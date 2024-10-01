package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.TaskManagerRedis;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.cli.DatashareCliOptions;
import org.icij.datashare.mode.CommonMode;
import org.jetbrains.annotations.NotNull;
import org.redisson.Redisson;
import org.redisson.RedissonMap;
import org.redisson.api.RedissonClient;
import org.redisson.command.CommandSyncService;
import org.redisson.liveobject.core.RedissonObjectBuilder;

@Singleton
public class TaskManagerAmqp extends org.icij.datashare.asynctasks.TaskManagerAmqp {

    @Inject
    public TaskManagerAmqp(AmqpInterlocutor amqp, RedissonClient redissonClient, PropertiesProvider propertiesProvider)
        throws IOException {
        this(amqp, redissonClient, propertiesProvider, null);
    }

    TaskManagerAmqp(AmqpInterlocutor amqp, RedissonClient redissonClient, PropertiesProvider propertiesProvider, Runnable eventCallback)  throws IOException {
        // We start with a fresh list of known task everytime, we could decide to allow inheriting
        // existing tasks
        super(amqp, createTaskQueue(redissonClient), Utils.getRoutingStrategy(propertiesProvider), eventCallback);
    }

    private static RedissonMap<String, Task<?>> createTaskQueue(RedissonClient redissonClient) {
        return new RedissonMap<>(new TaskManagerRedis.TaskViewCodec(),
            new CommandSyncService(((Redisson) redissonClient).getConnectionManager(),
                new RedissonObjectBuilder(redissonClient)),
            CommonMode.DS_TASK_MANAGER_QUEUE_NAME,
            redissonClient,
            null,
            null
        );
    }
}
