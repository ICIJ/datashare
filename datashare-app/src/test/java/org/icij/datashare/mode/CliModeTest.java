package org.icij.datashare.mode;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.TaskRunnerLoop;
import org.icij.datashare.asynctasks.TaskSupplier;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;


public class CliModeTest {
    @Rule public TemporaryFolder dataDir = new TemporaryFolder();

    @Test
    public void test_task_runner() throws Exception {
        CommonMode mode = CommonMode.create(PropertiesProvider.fromMap(new HashMap<>() {{
            put("dataDir", dataDir.getRoot().toString());
            put("mode", "TASK_RUNNER");
            put("batchQueueType", QueueType.REDIS.name());
        }}));

        TaskRunnerLoop taskRunnerLoop = new TaskRunnerLoop(mode.get(TaskFactory.class), mode.get(TaskSupplier.class));
        mode.get(TaskManager.class).shutdownAndAwaitTermination(1, TimeUnit.SECONDS); // to enqueue poison
        taskRunnerLoop.call();
        taskRunnerLoop.close();
        mode.get(Indexer.class).close();
    }
}
