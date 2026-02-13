package org.icij.datashare.asynctasks;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.asynctasks.Task.State.CANCELLED;
import static org.icij.datashare.asynctasks.Task.State.DONE;
import static org.icij.datashare.asynctasks.Task.State.ERROR;
import static org.icij.datashare.asynctasks.Task.State.RUNNING;
import static org.icij.datashare.asynctasks.TaskManagerTemporal.DEFAULT_NAMESPACE;
import static org.icij.datashare.asynctasks.TaskManagerTemporal.WORKFLOWS_DEFAULT;
import static org.icij.datashare.asynctasks.TaskManagerTemporal.buildClient;
import static org.icij.datashare.asynctasks.TaskManagerTemporal.deleteNamespace;
import static org.icij.datashare.asynctasks.TaskManagerTemporal.setupNamespace;
import static org.icij.datashare.asynctasks.temporal.TemporalHelper.createTemporalWorkerFactory;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.asynctasks.temporal.FailingActivityImpl;
import org.icij.datashare.asynctasks.temporal.FailingWorkflowImpl;
import org.icij.datashare.asynctasks.temporal.HelloWorldActivityImpl;
import org.icij.datashare.asynctasks.temporal.HelloWorldWorkflowImpl;
import org.icij.datashare.asynctasks.temporal.TemporalHelper;
import org.icij.datashare.tasks.RoutingStrategy;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

@Ignore("skipping this temporarily to avoid blocking")
public class TaskManagerTemporalIntTest {
    private static WorkflowClient client;

    private static TaskManagerTemporal taskManager;


    @BeforeClass
    public static void setUpClass() {
        client = buildClient(EnvUtils.resolve("temporalTarget", "temporal:7233"), DEFAULT_NAMESPACE);
        taskManager = new TaskManagerTemporal(client, RoutingStrategy.UNIQUE);
    }

    @Before
    public void setUp() throws IOException, InterruptedException {
        try {
            deleteNamespace(client, Duration.ofSeconds(5));
        } catch (StatusRuntimeException ex) {
            if (!ex.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
                throw ex;
            }
        }
        setupNamespace(client, Duration.ofSeconds(5));
        Thread.sleep(2000); // Sleep to allow custom attribute creation propagation refresh rate is 0.1s
    }

    private TemporalHelper.CloseableWorkerFactoryHandle testCloseableWorkerFactory(WorkflowClient client) {
        return new TemporalHelper.CloseableWorkerFactoryHandle(testWorkerFactory(client));
    }

    private WorkerFactory testWorkerFactory(WorkflowClient client) {
        WorkerFactory workerFactory = WorkerFactory.newInstance(client);
        createTemporalWorkerFactory(WORKFLOWS, workerFactory);
        return workerFactory;
    }

    private static final List<TemporalHelper.RegisteredWorkflow> WORKFLOWS = List.of(
        new TemporalHelper.RegisteredWorkflow(HelloWorldWorkflowImpl.class, WORKFLOWS_DEFAULT,
            List.of(new TemporalHelper.RegisteredActivity(HelloWorldActivityImpl::new, WORKFLOWS_DEFAULT))),
        new TemporalHelper.RegisteredWorkflow(FailingWorkflowImpl.class, WORKFLOWS_DEFAULT,
            List.of(new TemporalHelper.RegisteredActivity(FailingActivityImpl::new, WORKFLOWS_DEFAULT)))
    );


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
    public void test_get_tasks() throws IOException, InterruptedException {
        Task<String> foo = new Task<>("foo", User.local(), Map.of());
        Task<String> bar = new Task<>("bar", User.local(), Map.of());

        taskManager.startTask(foo);
        Thread.sleep(1000); // Sleep 1sec to test the sort on timestamp in ms
        taskManager.startTask(bar);

        TaskFilters filter = TaskFilters.empty();
        List<Task<?>> tasks = taskManager.getTasks(filter).toList();

        assertThat(tasks.size()).isEqualTo(2);
        assertThat(tasks.stream().map(Task::getId).toList()).isEqualTo(List.of(foo.id, bar.id));
    }

    @Test
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
    public void test_get_task_ids() throws IOException, InterruptedException {
        Task<String> foo = new Task<>("hello_world", User.local(), Map.of());
        Task<String> bar = new Task<>("hello_world", User.local(), Map.of());

        taskManager.startTask(foo);
        Task.State fooState = taskManager.getTask(foo.id).getState();
        Thread.sleep(1000); // Sleep 1sec to test the sort on timestamp in ms
        taskManager.startTask(bar);

        Task.State barState = taskManager.getTask(bar.id).getState();
        assertThat(fooState).isEqualTo(Task.State.RUNNING);
        assertThat(barState).isEqualTo(Task.State.RUNNING);
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
        try (TemporalHelper.CloseableWorkerFactoryHandle ignored = testCloseableWorkerFactory(client)) {
            Task<String> task = new Task<>("hello_world", User.local(), Map.of());

            taskManager.startTask(task);
            taskManager.waitTasksToBeDone(10, TimeUnit.SECONDS);
            Task<?> completed = taskManager.getTask(task.id);

            assertThat(completed.getState()).isEqualTo(DONE);
            assertThat(completed.getResult()).isEqualTo(new TaskResult<>("hello world"));
        }
    }

    @Test(timeout = 10000)
    public void test_task_error() throws IOException, InterruptedException {
        try (TemporalHelper.CloseableWorkerFactoryHandle ignored = testCloseableWorkerFactory(client)) {
            Task<String> task = new Task<>("failing", User.local(), Map.of());

            taskManager.startTask(task);
            boolean terminated = taskManager.awaitTermination(2, TimeUnit.SECONDS);
            assertTrue(terminated);
            Task<?> errored = taskManager.getTask(task.id);

            assertThat(errored.getState()).isEqualTo(ERROR);
            assertThat(errored.getError().toString()).contains("this is a failure");
        }
    }

    @Test
    public void test_clear() throws IOException {
        String taskId = taskManager.startTask("taskName1", User.local(), Map.of());

        assertThat(taskManager.getTasks().toList()).hasSize(1);
        taskManager.clear();

        assertThrows(UnknownTask.class, () -> taskManager.getTask(taskId));
        assertThat(taskManager.getTasks().toList()).hasSize(0);
    }

    @Test
    public void test_clear_task() throws IOException {
        String taskId = taskManager.startTask("taskName1", User.local(), Map.of());

        Task<?> clearedTask = taskManager.clearTask(taskId);

        assertThat(taskId).isEqualTo(clearedTask.id);
        assertThrows(UnknownTask.class, () -> taskManager.getTask(taskId));
        assertThat(taskManager.getTasks().toList()).hasSize(0);
    }

    @Ignore("keeping this one for manual test as it can take very long to complete")
    @Test
    public void test_clear_done_tasks() throws Exception {
        try (TemporalHelper.CloseableWorkerFactoryHandle ignored = testCloseableWorkerFactory(client)) {
            Task<String> task = new Task<>("hello_world", User.local(), Map.of("key", "value"));
            taskManager.startTask(task);
            assertThat(taskManager.awaitTermination(2, TimeUnit.SECONDS));
            assertThat(taskManager.getTasks().toList()).hasSize(1);

            taskManager.clearDoneTasks();

            taskManager.awaitCleared(Set.of(task.id), 30, TimeUnit.SECONDS);
        }
    }

    @Ignore("keeping this one for manual test as it can take very long to complete")
    @Test
    public void test_clear_done_tasks_with_args_filter() throws IOException, InterruptedException {
        try (TemporalHelper.CloseableWorkerFactoryHandle ignored = testCloseableWorkerFactory(client)) {
            Task<String> first = new Task<>("hello_world", User.local(), Map.of("key", "value"));
            Task<String> second = new Task<>("hello_world", User.local(), Map.of("otherKey", "otherValue"));
            taskManager.startTask(first);
            taskManager.startTask(second);
            assertThat(taskManager.awaitTermination(2, TimeUnit.SECONDS));
            assertThat(taskManager.getTaskIds().toList()).hasSize(2);
            TaskFilters filter = TaskFilters.empty().withArgs(new TaskFilters.ArgsFilter("otherKey", "otherValue"));

            taskManager.clearDoneTasks(filter);

            taskManager.awaitCleared(Set.of(second.id), 1, TimeUnit.MINUTES);
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

    @Test
    public void test_health_ok() throws IOException {
        assertThat(taskManager.getHealth()).isTrue();
    }

    @Test
    public void test_health_ko() throws Exception {
        WorkflowServiceStubsOptions serviceStubsOptions = WorkflowServiceStubsOptions.newBuilder()
            .setTarget("localhost:1111")
            .build();
        WorkflowServiceStubs serviceStub = WorkflowServiceStubs.newServiceStubs(serviceStubsOptions);
        WorkflowClient koClient = WorkflowClient.newInstance(
            serviceStub, WorkflowClientOptions.newBuilder().setNamespace(DEFAULT_NAMESPACE).build()
        );

        try (
            TaskManagerTemporal koTaskManager = new TaskManagerTemporal(koClient, RoutingStrategy.UNIQUE)) {
            assertThat(koTaskManager.getHealth()).isFalse();
        }
    }

}