package org.icij.datashare.asynctasks;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

import java.io.Serializable;
import java.util.Map;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

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
        Mockito.verify(supplier).result(ArgumentMatchers.eq(taskView.id), ArgumentMatchers.eq("Hello world!"));
    }

    @Test
    public void test_cancel_task() throws Exception {
        // Given
        TaskRunnerLoop app = new TaskRunnerLoop(registry, supplier);
        TaskView<Serializable> taskView = new TaskView<>(TestFactory.SleepForever.class.getName(), User.local(), Map.of());
        Mockito.when(supplier.get(ArgumentMatchers.anyInt(), ArgumentMatchers.any())).thenReturn(taskView, TaskView.nullObject());
        boolean requeue = false;

        // When
        Thread appThread = new Thread(app::call);
        appThread.start();
        TestUtils.awaitPredicate(2000, () -> sleepStarted(taskView));
        app.cancel(taskView.id, requeue);
        appThread.join();

        // Then
        verify(supplier).canceled(ArgumentMatchers.eq(taskView), ArgumentMatchers.eq(false));
    }

    @Test
    public void test_cancel_task_and_requeue() throws Exception {
        // Given
        TaskRunnerLoop app = new TaskRunnerLoop(registry, supplier);
        TaskView<Serializable> taskView = new TaskView<>(TestFactory.SleepForever.class.getName(), User.local(), Map.of());
        Mockito.when(supplier.get(ArgumentMatchers.anyInt(), ArgumentMatchers.any())).thenReturn(taskView, TaskView.nullObject());
        boolean requeue = true;

        // When
        Thread appThread = new Thread(app::call);
        appThread.start();
        TestUtils.awaitPredicate(20000, () -> sleepStarted(taskView));
        app.cancel(taskView.id, requeue);
        appThread.join();

        // Then
        verify(supplier).canceled(ArgumentMatchers.eq(taskView), ArgumentMatchers.eq(true));
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

        verify(supplier).canceled(ArgumentMatchers.eq(taskView), ArgumentMatchers.eq(false));
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    private boolean sleepStarted(TaskView<Serializable> task) {
        try {
            verify(supplier, atLeast(2)).progress(ArgumentMatchers.eq(task.id), ArgumentMatchers.any(Double.class));
            return true;
        } catch (AssertionError ignored) {
            return false;
        }
    }
}