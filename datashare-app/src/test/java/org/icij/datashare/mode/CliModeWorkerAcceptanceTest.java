package org.icij.datashare.mode;

import org.icij.datashare.EnvUtils;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.TaskSupplier;
import org.icij.datashare.asynctasks.TaskWorkerLoop;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.tasks.DatashareTaskManager;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.util.Arrays.asList;

@RunWith(Parameterized.class)
public class CliModeWorkerAcceptanceTest {
    private final CommonMode mode;

    @Parameterized.Parameters
    public static Collection<Object[]> mode() throws Exception {
        return asList(new Object[][]{
                {
                    CommonMode.create(Map.of(
                                "dataDir", "/tmp",
                                "mode", "TASK_WORKER",
                                "batchQueueType", QueueType.AMQP.name(),
                                "queueType", "REDIS",
                                "messageBusAddress", EnvUtils.resolveUri("amqp", "amqp://guest:guest@amqp:5672")
                        ))
                },
                {
                        CommonMode.create(Map.of(
                                "dataDir", "/tmp",
                                "mode", "TASK_WORKER",
                                "redisPoolSize", "4",
                                "batchQueueType", QueueType.REDIS.name(),
                                "redisAddress", EnvUtils.resolveUri("redis", "redis://redis:6379")
                        ))
                }
        });
    }

    public CliModeWorkerAcceptanceTest(CommonMode mode) {
        this.mode = mode;
    }

    @Test(timeout = 30000)
    public void test_task_worker() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        TaskWorkerLoop taskWorkerLoop = new TaskWorkerLoop(mode.get(DatashareTaskFactory.class), mode.get(TaskSupplier.class), workerStarted, 1000);
        Thread workerApp = new Thread(taskWorkerLoop::call);
        workerApp.start();
        workerStarted.await();

        mode.get(DatashareTaskManager.class).shutdown(); // to send shutdown
        mode.get(DatashareTaskManager.class).awaitTermination(1, TimeUnit.SECONDS);
        workerApp.join();
        mode.get(Indexer.class).close();
    }
}
