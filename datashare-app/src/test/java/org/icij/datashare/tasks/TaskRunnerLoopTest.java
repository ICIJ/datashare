package org.icij.datashare.tasks;

import org.icij.datashare.batch.BatchDownload;
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

import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.tasks.TaskView.State.DONE;
import static org.icij.datashare.text.Project.project;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyInt;

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class TaskRunnerLoopTest {
    @Mock TaskFactoryForTest factory;
    @Mock TaskSupplier supplier;

    @Test(timeout = 2000)
    public void test_loop() throws Exception {
        TaskRunnerLoop app = new TaskRunnerLoop(factory, supplier);
        TestTask task = new TestTask(234);
        TaskView<Serializable> taskView = new TaskView<>(TestTask.class.getName(), User.local(), new HashMap<>());
                when(factory.createTestTask(any(), any())).thenReturn(task);
        when(supplier.get(anyInt(), any())).thenReturn(taskView, TaskView.nullObject());

        Integer nb = app.call();

        assertThat(nb).isEqualTo(1);
        verify(supplier).result(eq(taskView.id), eq(234));
    }

    @Test(timeout = 2000)
    public void test_task_interrupted() throws Exception {
        TaskRunnerLoop app = new TaskRunnerLoop(factory, supplier);
        TestSleepingTask task = new TestSleepingTask(1500);
        TaskView<Serializable> taskView = new TaskView<>(TestSleepingTask.class.getName(), User.local(), new HashMap<>());
        when(factory.createTestSleepingTask(any(), any())).thenReturn(task);
        when(supplier.get(anyInt(), any())).thenReturn(taskView, TaskView.nullObject());

        Thread appThread = new Thread(app::call);
        appThread.start();
        appThread.interrupt();
        appThread.join();

        verify(supplier).canceled(eq(taskView), eq(false));
    }

    @Before
    public void setUp() {
        initMocks(this);
    }

    public interface TaskFactoryForTest extends TaskFactory {
        TestTask createTestTask(TaskView<Integer> taskView, BiFunction<String, Double, Void> updateCallback);
        TestSleepingTask createTestSleepingTask(TaskView<Integer> taskView, BiFunction<String, Double, Void> updateCallback);
    }
}