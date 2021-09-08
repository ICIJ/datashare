package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.LinkedBlockingQueue;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BatchDownloadLoopTestInt {
    private LinkedBlockingQueue<BatchDownload> batchDownloadQueue = new LinkedBlockingQueue<>();
    private TaskManagerRedis taskManager = new TaskManagerRedis(new PropertiesProvider(), "test:task:manager", batchDownloadQueue);

    @Test(timeout = 1000)
    public void test_batch_download_task_view_properties() {
        TaskFactory factory = mock(TaskFactory.class);
        when(factory.createDownloadRunner(any(), any())).thenReturn(mock(BatchDownloadRunner.class));
        BatchDownloadLoop batchDownloadLoop = new BatchDownloadLoop(batchDownloadQueue, factory, taskManager);

        batchDownloadQueue.add(new BatchDownload(Project.project("prj"), User.local(), "foo"));
        batchDownloadLoop.enqueuePoison();

        batchDownloadLoop.run();

        assertThat(taskManager.get()).hasSize(1);
    }

    @After
    public void clear() {
        taskManager.clearDoneTasks();
    }
}
