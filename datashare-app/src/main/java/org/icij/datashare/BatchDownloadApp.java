package org.icij.datashare;

import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.TaskRunnerLoop;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.text.indexing.Indexer;
import org.redisson.api.RedissonClient;

import java.util.Properties;


public class BatchDownloadApp {
    public static void start(Properties properties) throws Exception {
        CommonMode commonMode = CommonMode.create(properties);
        TaskRunnerLoop taskRunnerLoop = commonMode.get(TaskFactory.class).createTaskRunnerLoop();
        taskRunnerLoop.call();
        commonMode.get(Indexer.class).close();
        commonMode.get(RedissonClient.class).shutdown();
    }
}
