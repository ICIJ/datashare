package org.icij.datashare.mode;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.TaskWorkerLoop;
import org.icij.datashare.asynctasks.TaskSupplier;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;


public class CliModeTest {
    @Rule public TemporaryFolder dataDir = new TemporaryFolder();

    @Test
    public void test_task_worker() throws Exception {
        CommonMode mode = CommonMode.create(PropertiesProvider.fromMap(new HashMap<>() {{
            put("dataDir", dataDir.getRoot().toString());
            put("mode", "TASK_WORKER");
            put("batchQueueType", QueueType.REDIS.name());
        }}));

        TaskWorkerLoop taskWorkerLoop = new TaskWorkerLoop(mode.get(DatashareTaskFactory.class), mode.get(TaskSupplier.class));
        mode.get(TaskManager.class).shutdownAndAwaitTermination(1, TimeUnit.SECONDS); // to enqueue poison
        taskWorkerLoop.call();
        taskWorkerLoop.close();
        mode.get(Indexer.class).close();
    }
}
