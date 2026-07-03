package org.icij.datashare.asynctasks;

import static org.fest.assertions.Assertions.assertThat;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.test.LogbackCapturingRule;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.event.Level;

public class TaskManagerMemoryTest {
    @Rule
    public LogbackCapturingRule logbackCapturingRule = new LogbackCapturingRule();

    TaskFactory factory = new TestFactory();

    private TaskManagerMemory taskManager;
    private TaskInspector taskInspector;
    private final CountDownLatch waitForLoop = new CountDownLatch(1);

    @Before
    public void setUp() throws Exception {
        PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of(
            "taskManagerPollingIntervalMilliseconds", "1000"
        ));
        taskManager = new TaskManagerMemory(factory, new TaskRepositoryMemory(), propertiesProvider, waitForLoop);
        taskInspector = new TaskInspector(taskManager);
        waitForLoop.await();
    }

    @Test
    public void test_run_task() throws Exception {
        Task<Integer> task = new Task<>(TestFactory.HelloWorld.class.getName(), User.local(), Map.of("greeted", "world"));

        String tid = taskManager.startTask(task, new Group(TaskGroupType.Test));
        taskManager.awaitTermination(100, TimeUnit.MILLISECONDS);

        assertThat(taskManager.getTask(tid).getState()).isEqualTo(Task.State.DONE);
        assertThat(taskManager.getTask(tid).getResult()).isEqualTo(new TaskResult<>("Hello world!"));
        assertThat(taskManager.getTasks().toList()).hasSize(1);
    }

    @Test
    public void test_stop_current_task() throws Exception {
        Task<Integer> task = new Task<>(TestFactory.SleepForever.class.getName(), User.local(), Map.of("intParameter", 2000));
        String taskId = taskManager.startTask(task, new Group(TaskGroupType.Test));

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

        Group group = new Group(TaskGroupType.Test);
        taskManager.startTask(t1, group);
        taskManager.startTask(t2, group);

        taskInspector.awaitToBeStarted(t1.id, 1000);
        taskManager.stopTask(t2.id); // the second is still in the queue
        taskManager.stopTask(t1.id);

        taskManager.awaitTermination(1, TimeUnit.SECONDS);
        assertThat(t2.getState()).isEqualTo(Task.State.CANCELLED);
        assertThat(taskManager.numberOfExecutedTasks()).isEqualTo(0);
        assertThat(taskManager.getTasks().toList()).hasSize(2);
    }

    @Test
    public void test_clear_the_only_task() throws Exception {
        Task<Integer> task = new Task<>("sleep", User.local(), Map.of("intParameter", 12));

        taskManager.startTask(task, new Group(TaskGroupType.Test));
        taskManager.awaitTermination(1, TimeUnit.SECONDS);
        assertThat(taskManager.getTasks().toList()).hasSize(1);

        taskManager.clearTask(task.id);

        assertThat(taskManager.getTasks().toList()).hasSize(0);
    }

    @Test
    public void test_clear_cancelled_tasks() throws Exception {
        Task<Integer> queuedTask = new Task<>("sleep", User.local(), Map.of("intParameter", 12));
        Task<Integer> cancelledTask = new Task<>("sleep", User.local(), Map.of("intParameter", 0));

        taskManager.startTask(queuedTask, new Group(TaskGroupType.Test));
        taskManager.startTask(cancelledTask, new Group(TaskGroupType.Test));
        taskManager.stopTask(cancelledTask.getId());

        assertThat(taskManager.getTasks().toList()).hasSize(2);
        taskManager.clearDoneTasks();
        assertThat(taskManager.getTasks().toList()).hasSize(1);
    }

    @Test
    public void test_clear_cancelled_tasks_with_filter() throws Exception {
        Task<Integer> queuedTask = new Task<>("sleep", User.local(), Map.of("intParameter", 12));
        Task<Integer> cancelledTask = new Task<>("sleep", User.local(), Map.of("intParameter", 0));
        TaskFilters filters = TaskFilters.empty().withNames("sleep");

        taskManager.startTask(queuedTask, new Group(TaskGroupType.Test));
        taskManager.startTask(cancelledTask, new Group(TaskGroupType.Test));
        taskManager.stopTask(cancelledTask.getId());

        assertThat(taskManager.getTasks().toList()).hasSize(2);
        taskManager.clearDoneTasks(filters);
        assertThat(taskManager.getTasks().toList()).hasSize(1);
    }

    @Test(expected = IllegalStateException.class)
    public void test_clear_running_task_should_throw_exception() throws Exception {
        Task<Integer> task = new Task<>("sleep", User.local(), Map.of("intParameter", 12));

        taskManager.startTask(task, new Group(TaskGroupType.Test));
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
        taskManager.result("unknownId", new TaskResult<>(0.5));
        assertThat(logbackCapturingRule.logs(Level.WARN)).contains(
            "unknown task id <unknownId> for result=TaskResult[value=0.5] call");
    }

    @Test
    public void test_insert_task() throws TaskAlreadyExists, IOException, UnknownTask {
        Task<String> task = new Task<>("name", User.local(), new HashMap<>());

        taskManager.insert(task, null);

        assertThat(taskManager.getTasks().toList()).hasSize(1);
        assertThat(taskManager.getTask(task.id)).isNotNull();
    }

    @Test
    public void test_update_task() throws TaskAlreadyExists, IOException, UnknownTask {
        // Given
        Task<?> task = new Task<>("HelloWorld", User.local(), Map.of("greeted", "world"));
        Task<?> update = new Task<>(task.id, task.name, task.getState(), 0.5, DatashareTime.getNow(), 3, null, task.args, null, null);
        // When
        taskManager.insert(task, null);
        taskManager.update(update);
        Task<?> updated = taskManager.getTask(task.id);
        // Then
        assertThat(updated).isEqualTo(update);
    }

    @Test
    public void test_wait_task_to_be_done() throws Exception {
        taskManager.startTask(TestFactory.Sleep.class, User.local(), Map.of("duration", 100000));
        long unfinished = taskManager.waitTasksToBeDone(200, TimeUnit.MILLISECONDS);
        assert unfinished == 1L;
    }

    // Well-known args key a CLI run stamps on its tasks; the wait scopes on it via an
    // args filter. Duplicated as a literal here because datashare-tasks does not depend on
    // datashare-cli where DatashareCliOptions.PIPELINE_RUN_ID lives.
    private static final String RUN_ID = "pipelineRunId";

    @Test
    public void test_wait_scoped_to_run_id_leaves_a_peers_non_final_task_untouched() throws Exception {
        // A concurrent peer's task on the shared store: non-final, same (null) user, tagged
        // with a DIFFERENT run id. It is never enqueued to our loop so it stays QUEUED.
        Task<String> peer = new Task<>("sleep", User.nullUser(), Map.of("intParameter", 12, RUN_ID, "other-run"));
        taskManager.insert(peer, new Group(TaskGroupType.Test));
        peer.queue();
        taskManager.update(peer);

        // our own quick task, tagged with our run id
        Task<String> own = new Task<>(TestFactory.HelloWorld.class.getName(), User.nullUser(), Map.of("greeted", "world", RUN_ID, "my-run"));
        String ownId = taskManager.startTask(own, new Group(TaskGroupType.Test));

        long unfinished = taskManager.waitTasksToBeDone(
            TaskFilters.empty().withArgs(new TaskFilters.ArgsFilter(RUN_ID, "my-run")), 2, TimeUnit.SECONDS);

        assertThat(unfinished).isEqualTo(0L);
        assertThat(taskManager.getTask(ownId).getState()).isEqualTo(Task.State.DONE);
        // the peer's task was neither waited on nor cancelled: exactly where it was left
        assertThat(taskManager.getTask(peer.id).getState()).isEqualTo(Task.State.QUEUED);
    }

    @Test
    public void test_wait_scoped_to_run_id_covers_descendant_not_among_started_ids() throws Exception {
        // A descendant a running stage would spawn mid-flight (e.g. CreateNlpBatchesFromIndex
        // enqueuing a BatchNlpTask): tagged with our run id but never handed to the caller as
        // a "started" id. It stays non-final (not enqueued to our loop here).
        Task<String> descendant = new Task<>("sleep", User.nullUser(), Map.of("intParameter", 12, RUN_ID, "my-run"));
        taskManager.insert(descendant, new Group(TaskGroupType.Test));
        descendant.queue();
        taskManager.update(descendant);

        // Waiting on the run id (not on a fixed set of ids) keeps the run alive: the
        // descendant is still non-final, so the scoped wait reports it rather than returning
        // 0 and letting the caller shut the worker down on still-queued work.
        long unfinished = taskManager.waitTasksToBeDone(
            TaskFilters.empty().withArgs(new TaskFilters.ArgsFilter(RUN_ID, "my-run")), 500, TimeUnit.MILLISECONDS);

        assertThat(unfinished).isEqualTo(1L);
        assertThat(taskManager.getTask(descendant.id).getState()).isEqualTo(Task.State.QUEUED);
    }

    @Test
    public void test_health_ok() {
        assertThat(taskManager.getHealth()).isTrue();
    }

    @Test
    public void test_health_ko() throws IOException {
        taskManager.close();

        assertThat(taskManager.getHealth()).isFalse();
    }

    @After
    public void tearDown() throws Exception {
        taskManager.close();
    }

}
