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

        RBlockingQueue<BatchDownload> batchDownloadQueue = redissonClient.getBlockingQueue(DS_BATCHDOWNLOAD_QUEUE_NAME);
        bind(TaskManager.class).toInstance(new TaskManagerRedis(redissonClient, DS_TASK_MANAGER_QUEUE_NAME, batchDownloadQueue));
        configurePersistence();
    }
}
