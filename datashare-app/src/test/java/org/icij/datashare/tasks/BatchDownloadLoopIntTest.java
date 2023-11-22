package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_DOWNLOAD_DIR;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_DOWNLOAD_ZIP_TTL;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_BATCH_DOWNLOAD_DIR;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_BATCH_DOWNLOAD_ZIP_TTL;
import static org.icij.datashare.text.Project.project;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BatchDownloadLoopIntTest {
    private final LinkedBlockingQueue<TaskView<?>> batchDownloadQueue = new LinkedBlockingQueue<>();
    private final TaskManagerRedis taskManager = new TaskManagerRedis(new PropertiesProvider(), "test:task:manager", batchDownloadQueue);

    @Test(timeout = 1000)
    public void test_batch_download_task_view_properties() {
        TaskFactory factory = mock(TaskFactory.class);
        BatchDownload batchDownload = new BatchDownload(singletonList(project("prj")), User.local(), "foo");

        HashMap<String, Object> properties = new HashMap<>() {{
            put("batchDownload", batchDownload);
        }};
        TaskView<File> taskView = new TaskView<>(BatchDownloadRunner.class.getName(), batchDownload.user, properties);
        BatchDownloadRunner runner = new BatchDownloadRunner(mock(Indexer.class), new PropertiesProvider(), taskView, taskManager::progress);
        when(factory.createDownloadRunner(any(), any())).thenReturn(runner);

        PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
            put(BATCH_DOWNLOAD_ZIP_TTL, String.valueOf(DEFAULT_BATCH_DOWNLOAD_ZIP_TTL));
            put(BATCH_DOWNLOAD_DIR, DEFAULT_BATCH_DOWNLOAD_DIR);
        }});

        BatchDownloadLoop batchDownloadLoop = new BatchDownloadLoop(propertiesProvider, factory, taskManager);

        taskManager.startTask(BatchDownloadRunner.class.getName(), properties);
        taskManager.shutdownAndAwaitTermination(1, TimeUnit.SECONDS);

        batchDownloadLoop.run();

        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTasks().get(0).error).isNotNull();
        assertThat(taskManager.getTasks().get(0).getProgress()).isEqualTo(1);
        assertThat(taskManager.getTasks().get(0).properties).hasSize(1);
    }

    @After
    public void clear() {
        taskManager.clearDoneTasks();
    }
}
