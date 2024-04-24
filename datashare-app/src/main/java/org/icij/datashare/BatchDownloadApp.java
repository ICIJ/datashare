package org.icij.datashare;

import org.icij.datashare.asynctasks.TaskFactory;
import org.icij.datashare.asynctasks.TaskRunnerLoop;
import org.icij.datashare.asynctasks.TaskSupplier;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.text.indexing.Indexer;
import org.redisson.api.RedissonClient;

import java.util.Properties;


public class BatchDownloadApp {
    public static void start(Properties properties) throws Exception {
        CommonMode commonMode = CommonMode.create(properties);
        TaskRunnerLoop taskRunnerLoop = new TaskRunnerLoop(commonMode.get(TaskFactory.class), commonMode.get(TaskSupplier.class));
        taskRunnerLoop.call();
        commonMode.get(Indexer.class).close();
        commonMode.get(RedissonClient.class).shutdown();
    }
}
