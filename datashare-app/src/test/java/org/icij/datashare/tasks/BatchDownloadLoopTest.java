package org.icij.datashare.tasks;

import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.io.File;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class BatchDownloadLoopTest {
    private final BlockingQueue<BatchDownload> batchDownloadQueue = new LinkedBlockingQueue<>();
    @Mock BatchDownloadRunner batchRunner;
    @Mock TaskFactory factory;
    @Mock TaskManager manager;
    @Captor
    private ArgumentCaptor<TaskView<File>> argCaptor;

    @Test
    public void test_loop() throws Exception {
        BatchDownloadLoop app = new BatchDownloadLoop(batchDownloadQueue, factory, manager);
        batchDownloadQueue.add(new BatchDownload(project("prj"), User.local(), "query"));
        app.enqueuePoison();

        app.run();

        verify(batchRunner).call();
        verify(manager).save(argCaptor.capture());
        assertThat(argCaptor.getValue().getState()).isEqualTo(TaskView.State.DONE);
    }

    @Before
    public void setUp() {
        initMocks(this);
        when(factory.createDownloadRunner(any(), any())).thenReturn(batchRunner);
    }
}