package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.mode.CommonMode;
import org.redisson.api.RedissonClient;

@Singleton
public class TaskManagerAmqp extends org.icij.datashare.asynctasks.TaskManagerAmqp {

    @Inject
    public TaskManagerAmqp(AmqpInterlocutor amqp, RedissonClient redissonClient)
        throws IOException {
        this(amqp, redissonClient, null);
    }

    TaskManagerAmqp(AmqpInterlocutor amqp, RedissonClient redissonClient, Runnable eventCallback)  throws IOException {
        super(amqp, redissonClient, CommonMode.DS_TASK_MANAGER_QUEUE_NAME, eventCallback);
    }
}
