package org.icij.datashare;

import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.tasks.TaskRunnerLoop;
import org.icij.datashare.text.indexing.Indexer;
import org.redisson.api.RedissonClient;

import java.util.Properties;


public class TaskRunnerApp {
    public static void start(Properties properties) throws Exception {
        CommonMode mode = CommonMode.create(properties);
        TaskRunnerLoop batchSearchLoop = mode.get(TaskFactory.class).createTaskRunnerLoop();
        batchSearchLoop.call();
        batchSearchLoop.close();
        mode.get(Indexer.class).close();// to avoid being blocked
        mode.get(RedissonClient.class).shutdown();
    }
}
