package org.icij.datashare;

import org.icij.datashare.asynctasks.TaskRunnerLoop;
import org.icij.datashare.asynctasks.TaskSupplier;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.text.indexing.Indexer;
import org.redisson.api.RedissonClient;

import java.util.Properties;


public class TaskRunnerApp {
    public static void start(Properties properties) throws Exception {
        CommonMode mode = CommonMode.create(properties);
        TaskRunnerLoop batchSearchLoop = new TaskRunnerLoop(mode.get(DatashareTaskFactory.class), mode.get(TaskSupplier.class));
        batchSearchLoop.call();
        batchSearchLoop.close();
        mode.get(Indexer.class).close();// to avoid being blocked
        mode.get(RedissonClient.class).shutdown();
    }
}
