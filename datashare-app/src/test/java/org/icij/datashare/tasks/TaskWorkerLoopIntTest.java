package org.icij.datashare.tasks;

import java.util.List;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskWorkerLoop;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TaskWorkerLoopIntTest {
    private final TaskSupplierRedis taskSupplier = new TaskSupplierRedis(new PropertiesProvider());
    TaskManagerRedis taskManager;
    CountDownLatch eventWaiter;

    @Test(timeout = 20000)
    public void test_batch_download_task_view_properties() throws Exception {
        DatashareTaskFactory factory = mock(DatashareTaskFactory.class);
        BatchDownload batchDownload = new BatchDownload(singletonList(project("prj")), User.local(), "foo");
        Map<String, Object> properties = Map.of("batchDownload", batchDownload);
        Task taskView = new Task(BatchDownloadRunner.class.getName(), batchDownload.user, properties);
        BatchDownloadRunner runner = new BatchDownloadRunner(mock(Indexer.class), new PropertiesProvider(), taskView, taskView.progress(taskSupplier::progress));
        when(factory.createBatchDownloadRunner(any(), any())).thenReturn(runner);

        CountDownLatch workerStarted = new CountDownLatch(1);
        TaskWorkerLoop taskWorkerLoop = new TaskWorkerLoop(factory, taskSupplier, workerStarted);
        Thread worker = new Thread(taskWorkerLoop::call);
        worker.start();
        workerStarted.await();
        taskManager.startTask(BatchDownloadRunner.class.getName(), User.local(), properties);
        Thread.sleep(100); // this is a symptom of a possible flaky test but for now I can't figure out how to be event driven

        taskManager.awaitTermination(1, TimeUnit.SECONDS);
        eventWaiter.await();

        List<Task> tasks = taskManager.getTasks().toList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getError()).isNotNull();
        assertThat(tasks.get(0).getProgress()).isEqualTo(1);
        assertThat(tasks.get(0).args).hasSize(2);
        assertThat(tasks.get(0).getUser()).isEqualTo(User.local());
    }

    @Before
    public void setUp() throws Exception {
        eventWaiter = new CountDownLatch(2); // progress, error
        taskManager = new TaskManagerRedis(new PropertiesProvider(), "test:task:manager", eventWaiter::countDown, 100);
    }

    @After
    public void tearDown() throws Exception {
        taskManager.clear();
        taskManager.close();
    }
}
