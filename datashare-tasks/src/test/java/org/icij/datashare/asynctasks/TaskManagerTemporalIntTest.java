package org.icij.datashare.asynctasks;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.temporal.*;
import org.icij.datashare.tasks.RoutingStrategy;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mock;
import org.redisson.api.RedissonClient;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.LambdaExceptionUtils.rethrowConsumer;
import static org.icij.datashare.asynctasks.Task.State.*;
import static org.icij.datashare.asynctasks.TaskManagerTemporal.WORKFLOWS_DEFAULT;
import static org.icij.datashare.asynctasks.temporal.TemporalInterlocutor.DEFAULT_NAMESPACE;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.MockitoAnnotations.openMocks;

public class TaskManagerTemporalIntTest {
    private AutoCloseable mocks;
    static RedissonClient redissonClient = new RedissonClientFactory().withOptions(
            Options.from(new PropertiesProvider(Map.of("redisAddress", EnvUtils.resolveUri("redis", "redis://redis:6379"))).getProperties())).create();
    // Warning : the order is not guaranteed for redisson map for tasks
    static TaskRepository taskRepository = new TaskRepositoryRedis(redissonClient, "tasks:queue:test");
    @Mock
    private TaskFactory taskFactory;
    private static TemporalInterlocutor temporal;
    private static TaskManagerTemporal taskManager;
    private List<TemporalInterlocutor.RegisteredWorkflow> registeredWorkflows;

    @BeforeClass
    public static void setUpClass() throws InterruptedException {
        temporal = new TemporalInterlocutor(EnvUtils.resolve("temporalAddress", "temporal:7233"), DEFAULT_NAMESPACE);
        taskManager = new TaskManagerTemporal(temporal, taskRepository, RoutingStrategy.UNIQUE);
    }

    @Before
    public void setUp() throws IOException, InterruptedException {
        mocks = openMocks(this);
        try {
            temporal.deleteNamespace(Duration.ofSeconds(5));
        } catch (StatusRuntimeException ex) {
            if (!ex.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
                throw ex;
            }
        }
        registeredWorkflows = List.of(
            new TemporalInterlocutor.RegisteredWorkflow(
                HelloWorldWorkflowImpl.class,
                WORKFLOWS_DEFAULT,
                List.of(new TemporalInterlocutor.RegisteredActivity(temporal.activityFactory(HelloWorldActivityImpl.class, taskFactory, taskRepository, 1.0d), WORKFLOWS_DEFAULT))),
            new TemporalInterlocutor.RegisteredWorkflow(
                FailingWorkflowImpl.class,
                WORKFLOWS_DEFAULT,
                List.of(new TemporalInterlocutor.RegisteredActivity(temporal.activityFactory(FailingActivityImpl.class, taskFactory, taskRepository, 1.0d), WORKFLOWS_DEFAULT)))
        );

        temporal.setupNamespace(Duration.ofSeconds(5));
        taskManager.clear();
        Thread.sleep(2000); // Sleep to allow custom attribute creation propagation refresh rate is 0.1s
    }

    @Before
    public void tearDown() throws Exception {
        Optional.ofNullable(mocks).ifPresent(rethrowConsumer(AutoCloseable::close));
    }

    @Test
    public void test_start_task() throws IOException {
        String expectedTaskId = taskManager.startTask("taskName", User.local(), Map.of("key", "value"));

        List<String> taskIds = taskManager.getTaskIds().toList();
        assertThat(taskIds).hasSize(1);
        String taskId = taskIds.get(0);
        assertThat(taskId).isEqualTo(expectedTaskId);
        Task<?> task = taskManager.getTask(taskId);
        assertThat(task.getState()).isEqualTo(RUNNING);
    }

    @Test
    public void test_start_task_without_user() throws IOException {
        String expectedTaskId = taskManager.startTask("taskName", null, Map.of("key", "value"));

        List<String> taskIds = taskManager.getTaskIds().toList();
        assertThat(taskIds).hasSize(1);
        String taskId = taskIds.get(0);
        assertThat(taskId).isEqualTo(expectedTaskId);
        Task<?> task = taskManager.getTask(taskId);
        assertThat(task.getState()).isEqualTo(RUNNING);
    }

    @Test
    public void test_start_task_with_null_user() throws IOException {
        String expectedTaskId = taskManager.startTask("taskName", User.nullUser(), Map.of("key", "value"));

        List<String> taskIds = taskManager.getTaskIds().toList();
        assertThat(taskIds).hasSize(1);
        String taskId = taskIds.get(0);
        assertThat(taskId).isEqualTo(expectedTaskId);
        Task<?> task = taskManager.getTask(taskId);
        assertThat(task.getState()).isEqualTo(RUNNING);
    }

    @Test(timeout = 5000)
    public void test_get_tasks() throws IOException, InterruptedException {
        Task<String> foo = new Task<>("foo", User.local(), Map.of());
        Task<String> bar = new Task<>("bar", User.local(), Map.of());

        taskManager.startTask(foo);
        Thread.sleep(1000); // Sleep 1sec to test the sort on timestamp in ms
        taskManager.startTask(bar);

        TaskFilters filter = TaskFilters.empty();
        List<Task<?>> tasks;
        while (true) {
            tasks = taskManager.getTasks(filter).toList();
            try {
                assertThat(tasks.size()).isEqualTo(2);
                break;
            } catch (AssertionError ignored) {
                Thread.sleep(100);
            }
        }
        // The order is not guaranteed by Redisson map for tasks
        assertThat(tasks.stream().map(Task::getId).toList()).contains(foo.id, bar.id);
    }

    @Test(timeout = 5000)
    public void test_get_tasks_with_args_filter() throws IOException {
        Task<String> foo = new Task<>("foo", User.local(), Map.of("someKey", "fooValue"));
        Task<String> bar = new Task<>("bar", User.local(), Map.of("someKey", "barValue"));
        taskManager.startTask(foo);
        taskManager.startTask(bar);

        TaskFilters filter = TaskFilters.empty().withArgs(new TaskFilters.ArgsFilter("someKey", "fooValue"));
        List<Task<?>> tasks = taskManager.getTasks(filter).toList();

        assertThat(tasks.size()).isEqualTo(1);
        assertThat(tasks.get(0).id).isEqualTo(foo.id);
    }


    @Test
    public void test_get_task_ids() throws IOException {
        Task<String> foo = new Task<>("foo", User.local(), Map.of());
        Task<String> bar = new Task<>("bar", User.local(), Map.of());

        taskManager.startTask(foo);
        taskManager.startTask(bar);

        TaskFilters filter = TaskFilters.empty();
        // Strongly consistent, should return all task IDs right away
        List<String> taskIds = taskManager.getTaskIds(filter).toList();

        assertThat(taskIds.size()).isEqualTo(2);
        assertThat(new HashSet<>(taskIds)).isEqualTo(Set.of(foo.id, bar.id));
    }

    @Test
    public void test_get_task_ids_with_args_filter() throws IOException {
        Task<String> foo = new Task<>("foo", User.local(), Map.of("someKey", "fooValue"));
        Task<String> bar = new Task<>("bar", User.local(), Map.of("someKey", "barValue"));
        taskManager.startTask(foo);
        taskManager.startTask(bar);

        TaskFilters filter = TaskFilters.empty().withArgs(new TaskFilters.ArgsFilter("someKey", "fooValue"));
        List<String> taskIds = taskManager.getTaskIds(filter).toList();

        assertThat(taskIds.size()).isEqualTo(1);
        assertThat(taskIds).isEqualTo(List.of(foo.id));
    }

    @Test(timeout = 10000)
    public void test_get_task_result() throws IOException {
        try (TemporalInterlocutor.CloseableWorkerFactoryHandle ignored = testCloseableWorkerFactory(temporal)) {
            Task<String> task = new Task<>("hello-world", User.local(), Map.of("name", "world"));

            taskManager.startTask(task);
            taskManager.waitTasksToBeDone(10, TimeUnit.SECONDS);
            Task<?> completed = taskManager.getTask(task.id);

            assertThat(completed.getState()).isEqualTo(DONE);
            assertThat(completed.getResult()).isEqualTo(new TaskResult<>("hello world"));
        }
    }

    @Test(timeout = 20000)
    public void test_task_error() throws IOException, InterruptedException {
        try (TemporalInterlocutor.CloseableWorkerFactoryHandle ignored = testCloseableWorkerFactory(temporal)) {
            Task<String> task = new Task<>("failing", User.local(), Map.of());

            taskManager.startTask(task);
            boolean terminated = taskManager.awaitTermination(2, TimeUnit.SECONDS);
            assertTrue(terminated);
            Task<?> errored = taskManager.getTask(task.id);

            assertThat(errored.getState()).isEqualTo(ERROR);
            assertThat(errored.getError().toString()).contains("this is a failure");
        }
    }

    @Ignore("skip until the ultimately consistent temporal behavior is properly handled")
    @Test
    public void test_clear() throws IOException {
        String taskId = taskManager.startTask("taskName1", User.local(), Map.of());

        assertThat(taskManager.getTaskIds().toList()).hasSize(1);
        taskManager.clear();

        assertThrows(UnknownTask.class, () -> taskManager.getTask(taskId));
        assertThat(taskManager.getTaskIds().toList()).hasSize(0);
    }

    @Ignore("skip until the ultimately consistent temporal behavior is properly handled")
    @Test
    public void test_clear_task() throws IOException {
        String taskId = taskManager.startTask("taskName1", User.local(), Map.of());

        Task<?> clearedTask = taskManager.clearTask(taskId);

        assertThat(taskId).isEqualTo(clearedTask.id);
        assertThrows(UnknownTask.class, () -> taskManager.getTask(taskId));
        assertThat(taskManager.getTaskIds().toList()).hasSize(0);
    }

    @Ignore("keeping this one for manual test as it can take very long to complete")
    @Test
    public void test_clear_done_tasks() throws Exception {
        try (TemporalInterlocutor.CloseableWorkerFactoryHandle ignored = testCloseableWorkerFactory(temporal)) {
            Task<String> task = new Task<>("hello-world", User.local(), Map.of("key", "value"));
            taskManager.startTask(task);
            assertThat(taskManager.awaitTermination(2, TimeUnit.SECONDS));
            assertThat(taskManager.getTaskIds().toList()).hasSize(1);

            taskManager.clearDoneTasks();

            awaitClearedInTemporal(Set.of(task.id), 30, TimeUnit.SECONDS);
        }
    }

    @Ignore("keeping this one for manual test as it can take very long to complete")
    @Test
    public void test_clear_done_tasks_with_args_filter() throws IOException, InterruptedException {
        try (TemporalInterlocutor.CloseableWorkerFactoryHandle ignored = testCloseableWorkerFactory(temporal)) {
            Task<String> first = new Task<>("hello-world", User.local(), Map.of("key", "value"));
            Task<String> second = new Task<>("hello-world", User.local(), Map.of("otherKey", "otherValue"));
            taskManager.startTask(first);
            taskManager.startTask(second);
            assertThat(taskManager.awaitTermination(2, TimeUnit.SECONDS));
            assertThat(taskManager.getTaskIds().toList()).hasSize(2);
            TaskFilters filter = TaskFilters.empty().withArgs(new TaskFilters.ArgsFilter("otherKey", "otherValue"));

            taskManager.clearDoneTasks(filter);

            awaitClearedInTemporal(Set.of(second.id), 1, TimeUnit.MINUTES);
        }
    }

    /**
     * This test makes sure that when a TemporalActivityImpl is started, the Task it receives has
     * the id set to the workflowId (not Temporal's internal runId).
     * BatchSearchRunner relies on the Task.id to retrieve the Queries to run from the DB.
     // TODO : This is a design that relies on unwritten convention, so it should be changed
     *
     */
    @Test(timeout = 10000)
    public void test_task_id_matches_workflow_id() throws IOException {
        AtomicReference<String> capturedId = new AtomicReference<>();
        TaskFactory capturingFactory = new TaskFactory() {
            // The factory will capture the Task id that will be set in the Task
            public java.util.concurrent.Callable<String> createDoNothingTask(Task<?> task, java.util.function.Function<Double, Void> progress) {
                capturedId.set(task.id);
                return () -> task.id;
            }
        };
        List<TemporalInterlocutor.RegisteredWorkflow> workflows = List.of(
            new TemporalInterlocutor.RegisteredWorkflow(
                DoNothingWorkflowImpl.class,
                WORKFLOWS_DEFAULT,
                List.of(new TemporalInterlocutor.RegisteredActivity(
                    temporal.activityFactory(DoNothingActivityImpl.class, capturingFactory, taskRepository, 1.0d),
                    WORKFLOWS_DEFAULT
                ))
            )
        );

        try (TemporalInterlocutor.CloseableWorkerFactoryHandle ignored = temporal.createFactory(1, workflows)) {
            Task<String> task = new Task<>(DoNothingTask.class.getName(), User.local(), Map.of());

            taskManager.startTask(task);
            taskManager.waitTasksToBeDone(10, TimeUnit.SECONDS);

            assertThat(capturedId.get()).isEqualTo(task.id);
        }
    }

    @Test
    public void test_stop_task() throws IOException {
        Task<String> task = new Task<>("foo", User.local(), Map.of());

        taskManager.startTask(task);
        boolean stopped = taskManager.stopTask(task.getId());
        Task<?> stored = taskManager.getTask(task.id);

        assertThat(stopped).isTrue();
        assertThat(stored.getState()).isEqualTo(CANCELLED);
    }

    @Test
    public void test_stop_stopped_task() throws IOException {
        Task<String> task = new Task<>("foo", User.local(), Map.of());

        taskManager.startTask(task);
        boolean stopped = taskManager.stopTask(task.getId());
        assertThat(stopped).isTrue();
        boolean stoppedAgain = taskManager.stopTask(task.getId());
        assertThat(stoppedAgain).isFalse();
    }

    @Test(timeout = 15000)
    public void test_progress_is_stored_in_repository_and_temporal() throws Exception {
        TaskFactory progressFactory = new TaskFactory() {
            public Callable<String> createDoNothingTask(Task<?> task, Function<Double, Void> progress) {
                return () -> {
                    progress.apply(0.5);
                    return "done";
                };
            }
        };
        List<TemporalInterlocutor.RegisteredWorkflow> workflows = List.of(
            new TemporalInterlocutor.RegisteredWorkflow(
                DoNothingWorkflowImpl.class,
                WORKFLOWS_DEFAULT,
                List.of(new TemporalInterlocutor.RegisteredActivity(
                    temporal.activityFactory(DoNothingActivityImpl.class, progressFactory, taskRepository, 1d),
                    WORKFLOWS_DEFAULT
                ))
            )
        );

        try (TemporalInterlocutor.CloseableWorkerFactoryHandle ignored = temporal.createFactory(1, workflows)) {
            Task<String> task = new Task<>(DoNothingTask.class.getName(), User.local(), Map.of());
            taskManager.startTask(task);
            taskManager.waitTasksToBeDone(15, TimeUnit.SECONDS);

            // Repository side: progress must have reached 1.0
            Task<?> fromRepo = taskRepository.getTask(task.id);
            assertThat(fromRepo.getProgress()).isEqualTo(1.0);

            // Temporal side: search attributes must reflect final progress 1.0
            Task<?> fromTemporal = temporal.getTask(task.id);
            assertThat(fromTemporal.getProgress()).isEqualTo(1.0);
        }
    }

    private TemporalInterlocutor.CloseableWorkerFactoryHandle testCloseableWorkerFactory(TemporalInterlocutor temporal) {
        return temporal.createFactory(1, registeredWorkflows);
    }

    protected void awaitClearedInTemporal(Set<String> taskIds, int timeout, TimeUnit timeUnit) throws IOException {
        long startTime = System.currentTimeMillis();
        long maxDuration = timeUnit.toMillis(timeout);
        while ((System.currentTimeMillis() - startTime < maxDuration)) {
            if (taskManager.getTaskIds().noneMatch(taskIds::contains)) {
                return;
            }
            try {
                Thread.sleep(taskManager.getTerminationPollingInterval());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        Assert.fail("failed to clear task in " + timeout + " " + timeUnit);
    }
}