package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskWorkerLoop;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
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

public class TaskWorkerLoopIntTest {
    private final LinkedBlockingQueue<Task<?>> taskQueue = new LinkedBlockingQueue<>();
    private final TaskSupplierRedis taskSupplier = new TaskSupplierRedis(new PropertiesProvider());


    @Test(timeout=5000)
    public void test_batch_download_task_view_properties() throws Exception {
        CountDownLatch eventWaiter = new CountDownLatch(2); // progress, result(error)
        try (TaskManagerRedis taskManager = new TaskManagerRedis(new PropertiesProvider(), "test:task:manager", eventWaiter::countDown)) {
            DatashareTaskFactory factory = mock(DatashareTaskFactory.class);
            BatchDownload batchDownload = new BatchDownload(singletonList(project("prj")), User.local(), "foo");

            HashMap<String, Object> properties = new HashMap<>() {{
                put("batchDownload", batchDownload);
            }};
            Task<File> taskView = new Task<>(BatchDownloadRunner.class.getName(), batchDownload.user, properties);
            BatchDownloadRunner runner = new BatchDownloadRunner(mock(Indexer.class), new PropertiesProvider(), taskView, taskView.progress(taskSupplier::progress));
            when(factory.createBatchDownloadRunner(any(), any())).thenReturn(runner);

            TaskWorkerLoop taskWorkerLoop = new TaskWorkerLoop(factory, taskSupplier);

            taskManager.startTask(BatchDownloadRunner.class.getName(), User.local(), properties);
            taskManager.shutdownAndAwaitTermination(1, TimeUnit.SECONDS);

            taskWorkerLoop.call();
            eventWaiter.await();

            assertThat(taskManager.getTasks()).hasSize(1);
            assertThat(taskManager.getTasks().get(0).getError()).isNotNull();
            assertThat(taskManager.getTasks().get(0).getProgress()).isEqualTo(1);
            assertThat(taskManager.getTasks().get(0).args).hasSize(2);
            assertThat(taskManager.getTasks().get(0).getUser()).isEqualTo(User.local());
        }
    }
}
