package org.icij.datashare;

import com.rabbitmq.client.AMQP;
import org.icij.datashare.asynctasks.TaskWorkerLoop;
import org.icij.datashare.asynctasks.TaskSupplier;
import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.text.indexing.Indexer;
import org.redisson.api.RedissonClient;

import java.util.Properties;


public class TaskWorkerApp {
    public static void start(Properties properties) throws Exception {
        try (CommonMode mode = CommonMode.create(properties);
            TaskWorkerLoop batchSearchLoop = new TaskWorkerLoop(mode.get(DatashareTaskFactory.class), mode.get(TaskSupplier.class))
        ) {
            batchSearchLoop.call();
        }
    }
}
