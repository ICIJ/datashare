package org.icij.datashare.tasks;

import junit.framework.TestCase;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.mockito.Mock;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.icij.datashare.text.Project.project;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class BatchDownloadLoopTest extends TestCase {
    private final BlockingQueue<BatchDownload> batchDownloadQueue = new LinkedBlockingQueue<>();
    @Mock BatchDownloadRunner batchRunner;
    @Mock TaskFactory factory;

    public void test_loop() throws Exception {
        BatchDownloadLoop app = new BatchDownloadLoop(batchDownloadQueue, factory);
        batchDownloadQueue.add(new BatchDownload(project("prj"), User.local(), "query"));
        app.enqueuePoison();

        app.run();

        verify(batchRunner).call();
    }

    @Before
    public void setUp() {
        initMocks(this);
        when(factory.createDownloadRunner(any())).thenReturn(batchRunner);
    }
}