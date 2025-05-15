package org.icij.datashare.asynctasks;

import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.Fail.fail;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TaskWorkerLoopTest {
    TestFactory registry = new TestFactory();
    @Mock
    TaskSupplier supplier;

    @Test(timeout = 2000)
    public void test_loop() throws Exception {
        TaskWorkerLoop app = new TaskWorkerLoop(registry, supplier);
        Task taskView = new Task(TestFactory.HelloWorld.class.getName(), User.local(), Map.of("greeted", "world"));
        Mockito.when(supplier.get(ArgumentMatchers.anyInt(), ArgumentMatchers.any())).thenReturn(taskView);
        CountDownLatch taskStarted = whenTaskHasStarted(taskView.id);

        Thread appThread = new Thread(app::call);
        appThread.start();
        taskStarted.await(1, TimeUnit.SECONDS);
        app.exit();
        appThread.join();

        Mockito.verify(supplier).result(eq(taskView.id), eq(app.mapper.writeValueAsBytes("Hello world!")));
    }

    @Test(timeout = 2000)
    public void test_unknown_task() throws Exception {
        TaskWorkerLoop app = new TaskWorkerLoop(registry, supplier);
        Task taskView = new Task("unknown_task", User.local(), Map.of());

        try {
            app.handle(taskView);
            fail("NackException should be raised");
        } catch (NackException ne) {
            assertThat(ne.requeue).isTrue();
        }
    }

    @Test(timeout = 2000)
    public void test_cancel_task() throws Exception {
        TaskWorkerLoop app = new TaskWorkerLoop(registry, supplier);
        Task taskView = new Task(TestFactory.SleepForever.class.getName(), User.local(), Map.of());
        Mockito.when(supplier.get(ArgumentMatchers.anyInt(), ArgumentMatchers.any())).thenReturn(taskView);
        boolean requeue = false;
        CountDownLatch taskStarted = whenTaskHasStarted(taskView.id);

        Thread appThread = new Thread(app::call);
        appThread.start();
        taskStarted.await(1, TimeUnit.SECONDS);
        app.cancel(taskView.id, requeue);
        app.exit();
        appThread.join();

        verify(supplier).canceled(ArgumentMatchers.eq(taskView), ArgumentMatchers.eq(false));
    }

    @Test(timeout = 2000)
    public void test_cancel_task_and_requeue() throws Exception {
        TaskWorkerLoop app = new TaskWorkerLoop(registry, supplier);
        Task taskView = new Task(TestFactory.SleepForever.class.getName(), User.local(), Map.of());
        Mockito.when(supplier.get(ArgumentMatchers.anyInt(), ArgumentMatchers.any())).thenReturn(taskView);
        boolean requeue = true;
        CountDownLatch taskStarted = whenTaskHasStarted(taskView.id);

        Thread appThread = new Thread(app::call);
        appThread.start();
        taskStarted.await(1, TimeUnit.SECONDS);
        app.cancel(taskView.id, requeue);
        app.exit();
        appThread.join();

        verify(supplier).canceled(eq(taskView), eq(true));
    }

    @Test(timeout = 2000)
    public void test_task_interrupted() throws Exception {
        TaskWorkerLoop app = new TaskWorkerLoop(registry, supplier);
        Task taskView = new Task(TestFactory.SleepForever.class.getName(), User.local(), Map.of());
        Mockito.when(supplier.get(ArgumentMatchers.anyInt(), ArgumentMatchers.any())).thenReturn(taskView);
        CountDownLatch taskStarted = whenTaskHasStarted(taskView.id);

        Thread appThread = new Thread(app::call);
        appThread.start();
        taskStarted.await(1, TimeUnit.SECONDS);
        app.exit();
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