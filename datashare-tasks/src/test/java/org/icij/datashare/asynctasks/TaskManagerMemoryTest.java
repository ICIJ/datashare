package org.icij.datashare.asynctasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.tasks.RoutingStrategy;
import org.icij.datashare.test.LogbackCapturingRule;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.redisson.api.RedissonClient;
import org.slf4j.event.Level;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;


public class TaskManagerMemoryTest {
    @Rule
    public LogbackCapturingRule logbackCapturingRule = new LogbackCapturingRule();

    TaskFactory factory = new TestFactory();

    private TaskManagerMemory taskManager;
    private TaskInspector taskInspector;
    private final CountDownLatch waitForLoop = new CountDownLatch(1);

    @Before
    public void setUp() throws Exception {
        taskManager = new TaskManagerMemory(factory, new PropertiesProvider(), new TaskRepositoryMemory(), waitForLoop);
        taskInspector = new TaskInspector(taskManager);
        waitForLoop.await();
    }

    @Test
    public void test_run_task() throws Exception {
        Task<Integer> task = new Task<>(TestFactory.HelloWorld.class.getName(), User.local(), Map.of("greeted", "world"));

        String tid = taskManager.startTask(task);
        taskManager.awaitTermination(100, TimeUnit.MILLISECONDS);

        assertThat(taskManager.getTask(tid).getState()).isEqualTo(Task.State.DONE);
        assertThat(taskManager.getTask(tid).getResult()).isEqualTo("Hello world!");
        assertThat(taskManager.getTasks()).hasSize(1);
    }

    @Test
    public void test_stop_current_task() throws Exception {
        Task<Integer> task = new Task<>(TestFactory.SleepForever.class.getName(), User.local(), Map.of("intParameter", 2000));
        String taskId = taskManager.startTask(task);

        taskInspector.awaitToBeStarted(taskId, 10000);
        taskManager.stopTask(taskId);
        taskManager.awaitTermination(1, TimeUnit.SECONDS);

        assertThat(taskManager.getTask(taskId).getState()).isEqualTo(Task.State.CANCELLED);
        assertThat(taskManager.numberOfExecutedTasks()).isEqualTo(0);
    }

    @Test
    public void test_stop_queued_task() throws Exception {
        Task<Integer> t1 = new Task<>(TestFactory.SleepForever.class.getName(), User.local(), Map.of());
        Task<Integer> t2 = new Task<>(TestFactory.HelloWorld.class.getName(), User.local(), Map.of("greeted", "stucked task"));

        taskManager.startTask(t1);
        taskManager.startTask(t2);

        taskInspector.awaitToBeStarted(t1.id, 1000);
        taskManager.stopTask(t2.id); // the second is still in the queue
        taskManager.stopTask(t1.id);

        taskManager.awaitTermination(1, TimeUnit.SECONDS);
        assertThat(t2.getState()).isEqualTo(Task.State.CANCELLED);
        assertThat(taskManager.numberOfExecutedTasks()).isEqualTo(0);
        assertThat(taskManager.getTasks()).hasSize(2);
    }

    @Test
    public void test_clear_the_only_task() throws Exception {
        Task<Integer> task = new Task<>("sleep", User.local(), Map.of("intParameter", 12));

        taskManager.startTask(task);
        taskManager.awaitTermination(1, TimeUnit.SECONDS);
        assertThat(taskManager.getTasks()).hasSize(1);

        taskManager.clearTask(task.id);

        assertThat(taskManager.getTasks()).hasSize(0);
    }

    @Test(expected = IllegalStateException.class)
    public void test_clear_running_task_should_throw_exception() throws Exception {
        Task<Integer> task = new Task<>("sleep", User.local(), Map.of("intParameter", 12));

        taskManager.startTask(task);
        taskManager.awaitTermination(1, TimeUnit.SECONDS);
        taskManager.progress(task.id, 0.5);
        assertThat(taskManager.getTask(task.id).getState()).isEqualTo(Task.State.RUNNING);

        taskManager.clearTask(task.id);
    }

    @Test
    public void test_progress_on_unknown_task() throws Exception {
        taskManager.awaitTermination(1, TimeUnit.SECONDS);
        taskManager.progress("unknownId", 0.5);
        assertThat(logbackCapturingRule.logs(Level.WARN)).contains(
            "unknown task id <unknownId> for progress=0.5 call");
    }

    @Test
    public void test_result_on_unknown_task() throws Exception {
        taskManager.awaitTermination(1, TimeUnit.SECONDS);
        taskManager.result("unknownId", 0.5);
        assertThat(logbackCapturingRule.logs(Level.WARN)).contains(
            "unknown task id <unknownId> for result=0.5 call");
    }

    @Test
    public void test_wait_task_to_be_done() throws Exception {
        taskManager.startTask(TestFactory.Sleep.class, User.local(), Map.of("duration", 100));
        List<Task<?>> tasks = taskManager.waitTasksToBeDone(200, TimeUnit.MILLISECONDS);
        assertThat(tasks).hasSize(1);
    }

    @Test
    public void test_health_ok() {
        assertThat(taskManager.getHealth()).isTrue();
    }

    @Test
    public void test_health_ko() throws IOException {
        taskManager.shutdown();

        assertThat(taskManager.getHealth()).isFalse();
    }

    @After
    public void tearDown() throws Exception {
        taskManager.close();
    }

}
