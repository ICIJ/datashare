package org.icij.datashare.mode;

import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.tasks.TaskManager;
import org.icij.datashare.tasks.TaskManagerRedis;
import org.redisson.api.RBlockingQueue;

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
