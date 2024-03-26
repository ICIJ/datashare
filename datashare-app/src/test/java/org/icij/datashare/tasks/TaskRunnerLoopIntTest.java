package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TaskRunnerLoopIntTest {
    private final LinkedBlockingQueue<TaskView<?>> taskQueue = new LinkedBlockingQueue<>();
    private final TaskSupplierRedis taskSupplier = new TaskSupplierRedis(new PropertiesProvider(), taskQueue);


    @Test(timeout =3000)
    public void test_batch_download_task_view_properties() throws Exception {
        CountDownLatch eventWaiter = new CountDownLatch(2); // progress, result(error)
        try (TaskManagerRedis taskManager = new TaskManagerRedis(new PropertiesProvider(), "test:task:manager", taskQueue, eventWaiter::countDown)) {
            TaskFactory factory = mock(TaskFactory.class);
            BatchDownload batchDownload = new BatchDownload(singletonList(project("prj")), User.local(), "foo");

            HashMap<String, Object> properties = new HashMap<>() {{
                put("batchDownload", batchDownload);
            }};
            TaskView<File> taskView = new TaskView<>(BatchDownloadRunner.class.getName(), batchDownload.user, properties);
            BatchDownloadRunner runner = new BatchDownloadRunner(mock(Indexer.class), new PropertiesProvider(), taskView, taskSupplier::progress);
            when(factory.createBatchDownloadRunner(any(), any())).thenReturn(runner);

            TaskRunnerLoop taskRunnerLoop = new TaskRunnerLoop(factory, taskSupplier);

            taskManager.startTask(BatchDownloadRunner.class.getName(), User.local(), properties);
            taskManager.shutdownAndAwaitTermination(1, TimeUnit.SECONDS);

            taskRunnerLoop.call();
            eventWaiter.await();

            assertThat(taskManager.getTasks()).hasSize(1);
            assertThat(taskManager.getTasks().get(0).error).isNotNull();
            assertThat(taskManager.getTasks().get(0).getProgress()).isEqualTo(1);
            assertThat(taskManager.getTasks().get(0).properties).hasSize(1);
        }
    }
}
