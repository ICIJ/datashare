package org.icij.datashare.tasks;

import ch.qos.logback.classic.Level;
import com.google.inject.AbstractModule;
import com.google.inject.CreationException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.TypeLiteral;
import org.icij.datashare.test.LogbackCapturingRule;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static java.util.Optional.ofNullable;
import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;


public class TaskManagerMemoryTest {
    @Rule public LogbackCapturingRule logbackCapturingRule = new LogbackCapturingRule();
    @Mock TestFactory factory;
    private TaskManagerMemory taskManager;
    private final CountDownLatch waitForLoop = new CountDownLatch(1);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        LinkedBlockingQueue<TaskView<?>> taskViews = new LinkedBlockingQueue<>();
        try {
            Injector injector = Guice.createInjector(new AbstractModule() {
                @Override
                protected void configure() {
                    bind(new TypeLiteral<BlockingQueue<TaskView<?>>>() {}).toInstance(taskViews);
                    bind(TaskFactory.class).toInstance(factory);
                    bind(TaskManagerMemory.class).toInstance(new TaskManagerMemory(taskViews, factory, waitForLoop));
                }
            });
            taskManager = injector.getInstance(TaskManagerMemory.class);
        } catch (CreationException e) {
            System.out.println("cannot create injector: " + e.getErrorMessages());
            throw e;
        }
        waitForLoop.await();
    }

    @Test
    public void test_run_task() throws InterruptedException {
        TaskView<Integer> task = new TaskView<>(TestTask.class.getName(), User.local(), Map.of("intParameter", 12));
        when(factory.createTestTask(eq(task), any())).thenReturn(new TestTask(task));

        TaskView<Integer> t = taskManager.startTask(task);
        taskManager.shutdownAndAwaitTermination(1, TimeUnit.SECONDS);

        assertThat(taskManager.getTask(t.id).getResult()).isEqualTo(12);
        assertThat(taskManager.getTask(t.id).getState()).isEqualTo(TaskView.State.DONE);
        assertThat(taskManager.getTasks()).hasSize(1);
    }

    @Test
    public void test_stop_current_task() throws InterruptedException {
        TaskView<Integer> task = new TaskView<>(SleepingTestTask.class.getName(), User.local(), Map.of("intParameter", 2000));
        SleepingTestTask c = new SleepingTestTask(task);
        when(factory.createSleepingTestTask(eq(task), any())).thenReturn(c);

        TaskView<Integer> t = taskManager.startTask(task);
        c.awaitToBeStarted();
        taskManager.stopTask(t.id);
        taskManager.shutdownAndAwaitTermination(1, TimeUnit.SECONDS);

        assertThat(taskManager.getTask(t.id).getState()).isEqualTo(TaskView.State.CANCELLED);
        assertThat(taskManager.numberOfExecutedTasks()).isEqualTo(0);
    }

    @Test
    public void test_stop_queued_task() throws InterruptedException {
        TaskView<Integer> t1 = new TaskView<>(SleepingTestTask.class.getName(), User.local(), Map.of("intParameter", 2000));
        TaskView<Integer> t2 = new TaskView<>(SleepingTestTask.class.getName(), User.local(), Map.of("intParameter", 2000));
        SleepingTestTask c1 = new SleepingTestTask(t1);
        when(factory.createSleepingTestTask(eq(t1), any())).thenReturn(c1);
        when(factory.createSleepingTestTask(eq(t2), any())).thenReturn(new SleepingTestTask(t2));
        taskManager.startTask(t1);
        taskManager.startTask(t2);

        c1.awaitToBeStarted();
        taskManager.stopTask(t2.id); // the second is still in the queue
        taskManager.stopTask(t1.id);

        taskManager.shutdownAndAwaitTermination(1, TimeUnit.SECONDS);
        assertThat(t2.getState()).isEqualTo(TaskView.State.CANCELLED);
        assertThat(taskManager.numberOfExecutedTasks()).isEqualTo(0);
        assertThat(taskManager.getTasks()).hasSize(2);
    }

    @Test
    public void test_clear_the_only_task() throws InterruptedException {
        TaskView<Integer> task = new TaskView<>(TestTask.class.getName(), User.local(), Map.of("intParameter", 12));
        when(factory.createTestTask(eq(task), any())).thenReturn(new TestTask(task));
        taskManager.startTask(task);
        taskManager.shutdownAndAwaitTermination(1, TimeUnit.SECONDS);
        assertThat(taskManager.getTasks()).hasSize(1);

        taskManager.clearTask(task.id);

        assertThat(taskManager.getTasks()).hasSize(0);
    }

    @Test
    public void test_progress_on_unknown_task() throws InterruptedException {
        taskManager.shutdownAndAwaitTermination(1, TimeUnit.SECONDS);
        taskManager.progress("unknownId", 0.5);
        assertThat(logbackCapturingRule.logs(Level.WARN)).contains("unknown task id <unknownId> for progress=0.5 call");
    }

    @Test
    public void test_result_on_unknown_task() throws InterruptedException {
        taskManager.shutdownAndAwaitTermination(1, TimeUnit.SECONDS);
        taskManager.result("unknownId", 0.5);
        assertThat(logbackCapturingRule.logs(Level.WARN)).contains("unknown task id <unknownId> for result=0.5 call");
    }


    @After
    public void tearDown() throws Exception {
        taskManager.close();
    }

    public interface TestFactory extends TaskFactory {
        TestTask createTestTask(TaskView<Integer> taskView, BiFunction<String, Double, Void> updateFunction);
        SleepingTestTask createSleepingTestTask(TaskView<Integer> taskView, BiFunction<String, Double, Void> updateFunction);
    }

    public static class TestTask implements CancellableCallable<Integer> {
        protected final int intParameter;
        protected volatile String cancelTaskId = null;
        protected volatile Thread taskThread;
        private final CountDownLatch waitForTask = new CountDownLatch(1);

        public TestTask(TaskView<Integer> tv) {
            this.intParameter = (int) tv.properties.get("intParameter");
        }

        @Override
        public Integer call() throws Exception {
            waitForTask.countDown();
            taskThread = Thread.currentThread();
            ofNullable(cancelTaskId).ifPresent(CancelException::new);
            return intParameter;
        }

        @Override
        public void cancel(String taskId, boolean requeue) {
            cancelTaskId = taskId;
            taskThread.interrupt();
        }

        public void awaitToBeStarted() throws InterruptedException {
            waitForTask.await();
        }
    }

    public static class SleepingTestTask extends TestTask {

        public SleepingTestTask(TaskView<Integer> tv) {
            super(tv);
        }

        @Override
        public Integer call() throws Exception {
            Integer result = super.call();
            try {
                Thread.sleep(intParameter);
            } catch (InterruptedException iex) {
                throw new CancelException(cancelTaskId);
            }
            return result;
        }
    }
}
