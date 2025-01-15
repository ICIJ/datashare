package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.TaskManagerRedis;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepository;
import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.mode.CommonMode;
import org.redisson.Redisson;
import org.redisson.RedissonMap;
import org.redisson.api.RedissonClient;
import org.redisson.command.CommandSyncService;
import org.redisson.liveobject.core.RedissonObjectBuilder;

@Singleton
public class TaskManagerAmqp extends org.icij.datashare.asynctasks.TaskManagerAmqp {
    @Inject
    public TaskManagerAmqp(AmqpInterlocutor amqp, TaskRepository taskRepository, PropertiesProvider propertiesProvider)
        throws IOException {
        this(amqp, taskRepository, propertiesProvider, null);
    }

    TaskManagerAmqp(AmqpInterlocutor amqp, TaskRepository taskRepository, PropertiesProvider propertiesProvider, Runnable eventCallback)  throws IOException {
        super(amqp, taskRepository, Utils.getRoutingStrategy(propertiesProvider), eventCallback);
    }
}
