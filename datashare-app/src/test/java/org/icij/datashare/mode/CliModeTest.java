package org.icij.datashare.mode;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCliOptions;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.tasks.TaskManager;
import org.icij.datashare.tasks.TaskRunnerLoop;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_BATCH_DOWNLOAD_DIR;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_BATCH_DOWNLOAD_ZIP_TTL;


public class CliModeTest {
    @Rule public TemporaryFolder dataDir = new TemporaryFolder();

    @Test
    public void test_batch_search() throws Exception {
        CommonMode mode = CommonMode.create(PropertiesProvider.fromMap(new HashMap<>() {{
            put("dataDir", dataDir.getRoot().toString());
            put("mode", "BATCH_SEARCH");
            put("batchQueueType", QueueType.REDIS.name());
        }}));

        TaskRunnerLoop taskRunnerLoop = mode.get(TaskFactory.class).createTaskRunnerLoop();
        mode.get(TaskManager.class).shutdownAndAwaitTermination(1, TimeUnit.SECONDS); // to enqueue poison
        taskRunnerLoop.call();
        taskRunnerLoop.close();
        mode.get(Indexer.class).close();
    }

    @Test(timeout = 5000)
    public void test_batch_download() throws Exception {
        CommonMode mode = CommonMode.create(PropertiesProvider.fromMap(new HashMap<>() {{
            put("dataDir", dataDir.getRoot().toString());
            put("mode", "BATCH_DOWNLOAD");
            put("batchQueueType", QueueType.REDIS.name());
            put(DatashareCliOptions.BATCH_DOWNLOAD_ZIP_TTL_OPT, String.valueOf(DEFAULT_BATCH_DOWNLOAD_ZIP_TTL));
            put(DatashareCliOptions.BATCH_DOWNLOAD_DIR_OPT, DEFAULT_BATCH_DOWNLOAD_DIR);
        }}));

        TaskRunnerLoop taskRunnerLoop = mode.get(TaskFactory.class).createTaskRunnerLoop();
        mode.get(TaskManager.class).shutdownAndAwaitTermination(1, TimeUnit.SECONDS); // to enqueue poison
        taskRunnerLoop.call();
        taskRunnerLoop.close();
    }
}
