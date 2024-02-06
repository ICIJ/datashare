package org.icij.datashare.tasks;

import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.Serializable;
import java.util.HashMap;

import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyObject;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class TaskRunnerLoopTest {
    @Mock BatchDownloadRunner batchRunner;
    @Mock TaskFactory factory;
    @Mock TaskSupplier supplier;

    @Test(timeout = 2000)
    public void test_loop() throws Exception {
        TaskRunnerLoop app = new TaskRunnerLoop(factory, supplier);
        BatchDownload batchDownload = new BatchDownload(singletonList(project("prj")), User.local(), "query");
        TaskView<Serializable> task = new TaskView<>(BatchDownloadRunner.class.getName(), batchDownload.user, new HashMap<>() {{
            put("batchDownload", batchDownload);
        }});
        when(supplier.get(anyInt(), any())).thenReturn(task, TaskView.nullObject());

        Integer nb = app.call();

        assertThat(nb).isEqualTo(1);
        verify(batchRunner).call();
        verify(supplier).result(eq(task.id), anyObject());
    }

    @Before
    public void setUp() {
        initMocks(this);
        when(factory.createBatchDownloadRunner(any(), any())).thenReturn(batchRunner);
    }
}