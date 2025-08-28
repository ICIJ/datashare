package org.icij.datashare;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_TASK_PROGRESS_INTERVAL_SECONDS;
import static org.icij.datashare.cli.DatashareCliOptions.TASK_PROGRESS_INTERVAL_OPT;

import org.icij.datashare.asynctasks.TaskFactory;
import org.icij.datashare.asynctasks.TaskWorkerLoop;
import org.icij.datashare.asynctasks.TaskSupplier;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.text.indexing.Indexer;
import org.redisson.api.RedissonClient;

import java.util.Properties;


public class BatchDownloadApp {
    public static void start(Properties properties) throws Exception {
        CommonMode commonMode = CommonMode.create(properties);
        double progressMinIntervalS = (double) ofNullable(properties.get(TASK_PROGRESS_INTERVAL_OPT))
                .orElse(DEFAULT_TASK_PROGRESS_INTERVAL_SECONDS);
        TaskWorkerLoop taskWorkerLoop = new TaskWorkerLoop(commonMode.get(TaskFactory.class), commonMode.get(TaskSupplier.class), progressMinIntervalS);
        taskWorkerLoop.call();
        commonMode.get(Indexer.class).close();
        commonMode.get(RedissonClient.class).shutdown();
    }
}
