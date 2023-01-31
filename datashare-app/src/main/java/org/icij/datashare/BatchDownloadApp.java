package org.icij.datashare;

import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.BatchDownloadLoop;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.text.indexing.Indexer;
import org.redisson.api.RedissonClient;

import java.util.Properties;


public class BatchDownloadApp {
    public static void start(Properties properties) throws Exception {
        CommonMode commonMode = CommonMode.create(properties);
        BatchDownloadLoop batchDownloadLoop = commonMode.get(TaskFactory.class).createBatchDownloadLoop();
        batchDownloadLoop.run();
        batchDownloadLoop.close();
        commonMode.get(Indexer.class).close();
        commonMode.get(RedissonClient.class).shutdown();
    }
}
