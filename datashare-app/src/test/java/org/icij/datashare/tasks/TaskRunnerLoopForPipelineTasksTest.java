package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.Serializable;
import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class TaskRunnerLoopForPipelineTasksTest {
    @Mock TaskFactory taskFactory;
    @Mock BiFunction<String, Double, Void> updateCallback;
    private final BlockingQueue<TaskView<?>> taskQueue = new LinkedBlockingQueue<>();
    private final TaskManagerMemory taskSupplier = new TaskManagerMemory(new PropertiesProvider(), taskQueue);

    @Test
    public void test_scan_task() throws InterruptedException {
        TaskView<Integer> task = new TaskView<>(ScanTask.class.getName(), User.local(), new HashMap<>() {{
            put("dataDir", "/path/to/files");
        }});
        ScanTask taskRunner = new ScanTask(mock(DocumentCollectionFactory.class), task, updateCallback);
        when(taskFactory.createScanTask(any(), any())).thenReturn(taskRunner);

        TaskRunnerLoop loop = new TaskRunnerLoop(taskFactory, taskSupplier);
        taskSupplier.startTask(ScanTask.class.getName(), User.local(), task.properties);
        taskSupplier.shutdownAndAwaitTermination(1, TimeUnit.SECONDS);

        loop.call();

        assertThat(taskSupplier.getTasks()).hasSize(1);
        assertThat(taskSupplier.getTasks().get(0).name).isEqualTo("org.icij.datashare.tasks.ScanTask");
    }

    @Before
    public void setUp() {
        initMocks(this);
    }
}
