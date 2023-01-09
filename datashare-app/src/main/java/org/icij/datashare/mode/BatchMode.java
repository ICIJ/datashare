package org.icij.datashare.mode;

import org.icij.datashare.tasks.TaskManager;
import org.icij.datashare.tasks.TaskManagerRedis;

import java.util.Properties;

public class BatchMode extends CommonMode {
    BatchMode(Properties properties) {
        super(properties);
    }

    @Override
    protected void configure() {
        super.configure();

        String batchQueueType = propertiesProvider.get("batchQueueType").orElse("org.icij.datashare.extract.MemoryBlockingQueue");
        bind(TaskManager.class).toInstance(new TaskManagerRedis(propertiesProvider, getBlockingQueue(propertiesProvider, batchQueueType, "ds:batchdownload:queue")));
        configurePersistence();
    }
}
