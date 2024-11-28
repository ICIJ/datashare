package org.icij.datashare;

import org.icij.datashare.asynctasks.TaskFactory;
import org.icij.datashare.asynctasks.TaskWorkerLoop;
import org.icij.datashare.asynctasks.TaskSupplier;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.text.indexing.Indexer;
import org.redisson.api.RedissonClient;

import java.util.Properties;


public class BatchDownloadApp {
    public static void start(Properties properties) throws Exception {
        CommonMode commonMode = CommonMode.create(properties);
        TaskWorkerLoop taskWorkerLoop = new TaskWorkerLoop(commonMode.get(DatashareTaskFactory.class), commonMode.get(TaskSupplier.class));
        taskWorkerLoop.call();
        commonMode.get(Indexer.class).close();
        commonMode.get(RedissonClient.class).shutdown();
    }
}
