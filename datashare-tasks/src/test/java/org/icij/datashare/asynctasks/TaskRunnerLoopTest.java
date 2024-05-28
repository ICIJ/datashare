package org.icij.datashare.asynctasks;

import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TaskRunnerLoopTest {
    TestFactory registry = new TestFactory();
    @Mock
    TaskSupplier supplier;

    @Test(timeout = 2000)
    public void test_loop() throws Exception {
        TaskRunnerLoop app = new TaskRunnerLoop(registry, supplier);
        TaskView<Serializable> taskView = new TaskView<>(TestFactory.HelloWorld.class.getName(), User.local(), Map.of("greeted", "world"));
        Mockito.when(supplier.get(ArgumentMatchers.anyInt(), ArgumentMatchers.any())).thenReturn(taskView, TaskView.nullObject());

        Integer nb = app.call();

        assertThat(nb).isEqualTo(1);
        Mockito.verify(supplier).result(eq(taskView.id), eq("Hello world!"));
    }

    @Test
    public void test_cancel_task() throws Exception {
        TaskRunnerLoop app = new TaskRunnerLoop(registry, supplier);
        TaskView<Serializable> taskView = new TaskView<>(TestFactory.SleepForever.class.getName(), User.local(), Map.of());
        Mockito.when(supplier.get(ArgumentMatchers.anyInt(), ArgumentMatchers.any())).thenReturn(taskView, TaskView.nullObject());
        boolean requeue = false;
        CountDownLatch taskStarted = whenTaskHasStarted(taskView.id);

        Thread appThread = new Thread(app::call);
        appThread.start();
        taskStarted.await(1, TimeUnit.SECONDS);
        app.cancel(taskView.id, requeue);
        appThread.join();

        verify(supplier).canceled(ArgumentMatchers.eq(taskView), ArgumentMatchers.eq(false));
    }

    @Test
    public void test_cancel_task_and_requeue() throws Exception {
        TaskRunnerLoop app = new TaskRunnerLoop(registry, supplier);
        TaskView<Serializable> taskView = new TaskView<>(TestFactory.SleepForever.class.getName(), User.local(), Map.of());
        Mockito.when(supplier.get(ArgumentMatchers.anyInt(), ArgumentMatchers.any())).thenReturn(taskView, TaskView.nullObject());
        boolean requeue = true;
        CountDownLatch taskStarted = whenTaskHasStarted(taskView.id);

        Thread appThread = new Thread(app::call);
        appThread.start();
        taskStarted.await(1, TimeUnit.SECONDS);
        app.cancel(taskView.id, requeue);
        appThread.join();

        verify(supplier).canceled(eq(taskView), eq(true));
    }

    @Test(timeout = 2000)
    public void test_task_interrupted() throws Exception {
        TaskRunnerLoop app = new TaskRunnerLoop(registry, supplier);
        TaskView<Serializable> taskView = new TaskView<>(TestFactory.SleepForever.class.getName(), User.local(), Map.of());
        Mockito.when(supplier.get(ArgumentMatchers.anyInt(), ArgumentMatchers.any()))
            .thenReturn(taskView, TaskView.nullObject());

        Thread appThread = new Thread(app::call);
        appThread.start();
        appThread.interrupt();
        appThread.join();

        verify(supplier).canceled(eq(taskView), eq(false));
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    private CountDownLatch whenTaskHasStarted(String id) {
        CountDownLatch latch = new CountDownLatch(2);
        when(supplier.progress(eq(id), anyDouble())).thenAnswer(invocationOnMock -> {
            latch.countDown();
            return null;
        });
        return latch;
    }
}