package org.icij.datashare.asynctasks;

import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.Serializable;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.Fail.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TaskWorkerLoopTest {
    TestFactory registry = new TestFactory();
    @Mock
    TaskSupplier supplier;

    @Test(timeout = 2000)
    public void test_loop() throws Exception {
        TaskWorkerLoop app = new TaskWorkerLoop(registry, supplier);
        Task<Serializable> taskView = new Task<>(TestFactory.HelloWorld.class.getName(), User.local(), Map.of("greeted", "world"));
        //Return a task once, then return null to avoid the WorkerLoop processing multiple Tasks
        Mockito.when(supplier.get(ArgumentMatchers.anyInt(), ArgumentMatchers.any())).thenReturn(taskView).thenReturn(null);
        CountDownLatch taskStarted = whenTaskHasStarted(taskView.id);

        Thread appThread = new Thread(app::call);
        appThread.start();
        taskStarted.await(1, TimeUnit.SECONDS);
        app.exit();
        appThread.join();

        Mockito.verify(supplier).result(eq(taskView.id), eq(new TaskResult<>("Hello world!")));
    }

    @Test(timeout = 2000)
    public void test_unknown_task() throws Exception {
        TaskWorkerLoop app = new TaskWorkerLoop(registry, supplier);
        Task<Serializable> taskView = new Task<>("unknown_task", User.local(), Map.of());

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
        Task<Serializable> taskView = new Task<>(TestFactory.SleepForever.class.getName(), User.local(), Map.of());
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
        Task<Serializable> taskView = new Task<>(TestFactory.SleepForever.class.getName(), User.local(), Map.of());
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
        Task<Serializable> taskView = new Task<>(TestFactory.SleepForever.class.getName(), User.local(), Map.of());
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

    @Test(timeout = 5000)
    public void test_graceful_shutdown_lets_running_task_finish() throws Exception {
        TaskWorkerLoop app = new TaskWorkerLoop(registry, supplier);
        Task<Serializable> task = new Task<>(TestFactory.Sleep.class.getName(), User.local(), Map.of("duration", 500));
        when(supplier.get(anyInt(), any())).thenReturn(task).thenReturn(null);
        CountDownLatch started = whenTaskHasStartedOnFirstProgress(task.id);
        Thread appThread = new Thread(app::call);
        appThread.start();
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        app.stopAcceptingTasks();
        app.awaitCurrentTask(Duration.ofSeconds(3));
        appThread.join();

        verify(supplier).result(eq(task.id), eq(new TaskResult<>(500)));
        verify(supplier, never()).canceled(any(), anyBoolean());
    }

    @Test(timeout = 5000)
    public void test_graceful_shutdown_requeues_task_when_timeout_is_reached() throws Exception {
        TaskWorkerLoop app = new TaskWorkerLoop(registry, supplier);
        Task<Serializable> task = new Task<>(TestFactory.SleepForever.class.getName(), User.local(), Map.of());
        when(supplier.get(anyInt(), any())).thenReturn(task).thenReturn(null);
        CountDownLatch started = whenTaskHasStarted(task.id);
        Thread appThread = new Thread(app::call);
        appThread.start();
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        app.stopAcceptingTasks();
        app.awaitCurrentTask(Duration.ofMillis(200));
        appThread.join();

        verify(supplier).canceled(eq(task), eq(true));
        verify(supplier, never()).result(any(), any());
    }

    @Test(timeout = 5000)
    public void test_graceful_shutdown_does_not_start_new_tasks() throws Exception {
        TaskWorkerLoop app = new TaskWorkerLoop(registry, supplier);
        Task<Serializable> task = new Task<>(TestFactory.HelloWorld.class.getName(), User.local(), Map.of("greeted", "world"));
        app.stopAcceptingTasks();

        app.handle(task);

        verify(supplier).canceled(eq(task), eq(true));
        verify(supplier, never()).result(any(), any());
    }

    @Test(timeout = 5000)
    public void test_graceful_shutdown_when_idle_returns_immediately() throws Exception {
        TaskWorkerLoop app = new TaskWorkerLoop(registry, supplier);
        when(supplier.get(anyInt(), any())).thenAnswer(i -> {
            Thread.sleep(60_000);
            return null;
        });
        Thread appThread = new Thread(app::call);
        appThread.start();
        Thread.sleep(100);

        app.stopAcceptingTasks();
        app.awaitCurrentTask(Duration.ofSeconds(3));

        appThread.join();
    }

    @Test(timeout = 1000)
    public void test_graceful_shutdown_does_not_wait_for_task_cancelled_before_start() throws Exception {
        TaskWorkerLoop app = new TaskWorkerLoop(registry, supplier);
        Task<Serializable> task = new Task<>(TestFactory.HelloWorld.class.getName(), User.local(), Map.of("greeted", "world"));
        app.cancel(task.id, true);

        app.handle(task);
        app.stopAcceptingTasks();
        app.awaitCurrentTask(Duration.ofSeconds(60));

        assertThat(app.currentTask.get()).isNull();
        verify(supplier).canceled(eq(task), eq(true));
        verify(supplier, never()).result(any(), any());
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    private CountDownLatch whenTaskHasStartedOnFirstProgress(String id) {
        CountDownLatch latch = new CountDownLatch(1);
        when(supplier.progress(eq(id), anyDouble())).thenAnswer(invocationOnMock -> {
            latch.countDown();
            return null;
        });
        return latch;
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