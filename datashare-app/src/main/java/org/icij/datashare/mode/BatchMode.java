package org.icij.datashare.mode;

import org.icij.datashare.tasks.TaskManager;
import org.icij.datashare.tasks.TaskManagerRedis;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.redisson.api.RedissonClient;

import java.util.Properties;

public class BatchMode extends CommonMode {
    BatchMode(Properties properties) {
        super(properties);
    }

    @Override
    protected void configure() {
        super.configure();
        bind(TaskManager.class).to(TaskManagerRedis.class).asEagerSingleton();
        configurePersistence();
    }
}
