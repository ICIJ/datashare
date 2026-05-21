package org.icij.datashare.asynctasks;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.LambdaExceptionUtils.rethrowConsumer;
import static org.icij.datashare.asynctasks.Task.State.ERROR;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import io.temporal.client.WorkflowStub;
import java.io.IOException;
import java.io.Serializable;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.asynctasks.temporal.TemporalInterlocutor;
import org.icij.datashare.tasks.RoutingStrategy;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;


public class TaskManagerTemporalTest {
    private AutoCloseable mocks;
    @Mock
    private TemporalInterlocutor temporal;
    @Mock
    private TaskRepository taskRepository;
    private TaskManagerTemporal taskManager;
    @Mock
    private WorkflowStub workflowStubMock;

    @Before
    public void setUp() throws Exception {
        mocks = openMocks(this);
        when(workflowStubMock.getResultAsync(any())).thenReturn(new CompletableFuture<>());
        when(temporal.createWorkflowStub(anyString())).thenReturn(workflowStubMock);
        doNothing().when(temporal).createWorkflow(anyString(), anyString(), anyString(), any(), any());
        taskManager = new TaskManagerTemporal(temporal, taskRepository, RoutingStrategy.UNIQUE);
    }

    @After
    public void tearDown() throws Exception {
        Optional.ofNullable(mocks).ifPresent(rethrowConsumer(AutoCloseable::close));
    }

    @Test
    public void test_start_task_with_group_routing() throws Exception {
        Task<String> task = new Task<>("taskName", User.local(), Map.of("someKey", "fooValue"));
        try (TaskManagerTemporal groupTaskManager = new TaskManagerTemporal(temporal, taskRepository, RoutingStrategy.GROUP)) {
            groupTaskManager.startTask(task, new Group(TaskGroupType.Test));

            verify(temporal).createWorkflow(anyString(), anyString(), eq("test"), any(), any());
        }
    }

    @Test
    public void test_start_task_with_name_routing() throws Exception {
        Task<String> task = new Task<>("taskName", User.local(), Map.of());
        try (TaskManagerTemporal groupTaskManager = new TaskManagerTemporal(temporal, taskRepository, RoutingStrategy.NAME)) {
            groupTaskManager.startTask(task, new Group(TaskGroupType.Test));

            verify(temporal).createWorkflow(anyString(), anyString(), eq("taskname"), any(), any());
        }
    }

    @Test
    public void test_clear_done_tasks_with_state_filter() throws IOException {
        Task<String> task = new Task<>("someTask", User.local(), Map.of());
        when(taskRepository.getTasks(any())).thenReturn(Stream.of(task));

        taskManager.clearDoneTasks(TaskFilters.empty().withStates(Set.of(ERROR)));

        verify(taskRepository).getTasks(argThat(f -> f.getStates().equals(Set.of(ERROR))));
        verify(taskRepository).delete(task.id);
    }

    @Test
    public void test_stop_unknown_task() {
        when(temporal.terminateWorkflow(anyString())).thenThrow(new UnknownTask("unknown-id"));
        assertThat(assertThrows(UnknownTask.class, () -> taskManager.stopTask("unknown-id")));
    }

    @Test
    public void test_reconcile_tasks_attaches_listener_for_running_task() throws Exception {
        Task<String> runningTask = new Task<>("taskName", User.local(), Map.of());
        runningTask.setState(Task.State.RUNNING);
        when(taskRepository.getTasks(argThat(f -> f.getStates().equals(Set.of(Task.State.RUNNING)))))
            .thenReturn(Stream.of(runningTask));
        doReturn(runningTask).when(temporal).getTask(runningTask.id);

        taskManager.reconcileTasks();

        assertThat(taskManager.pendingListeners.get(runningTask.id)).isNotNull();
    }

    @Test
    public void test_reconcile_Tasks_updates_repo_for_finished_task() throws Exception {
        Task<String> runningTask = new Task<>("taskName", User.local(), Map.of());
        runningTask.setState(Task.State.RUNNING);
        Task<String> doneTask = new Task<>(runningTask.id, runningTask.name, Task.State.DONE, 1.0, runningTask.createdAt, 0, null, runningTask.args, new TaskResult<>("result"), null);
        when(taskRepository.getTasks(argThat(f -> f.getStates().equals(Set.of(Task.State.RUNNING)))))
            .thenReturn(Stream.of(runningTask));
        doReturn(doneTask).when(temporal).getTask(runningTask.id);

        taskManager.reconcileTasks();

        verify(taskRepository).update(argThat(t -> t.getState() == Task.State.DONE));
    }

    @Test
    public void test_completion_listener_updates_repo_on_success() throws Exception {
        CompletableFuture<Serializable> future = new CompletableFuture<>();
        when(workflowStubMock.getResultAsync(any(Class.class))).thenReturn(future);

        Task<String> task = new Task<>("taskName", User.local(), Map.of());
        Task<String> doneTaskInTemporal = new Task<>(task.id, task.name, Task.State.DONE, 1.0, task.createdAt, 0, null, task.args, new TaskResult<>("result"), null);
        doReturn(task).when(taskRepository).getTask(task.id);
        doReturn(doneTaskInTemporal).when(temporal).getTask(task.id);
        taskManager.startTask(task, new Group(TaskGroupType.Test));

        future.complete("someResult");

        verify(taskRepository, atLeastOnce()).update(argThat(t -> t.getState() == Task.State.DONE));
    }

    @Test
    public void test_completion_listener_updates_repo_on_error() throws Exception {
        CompletableFuture<Serializable> future = new CompletableFuture<>();
        when(workflowStubMock.getResultAsync(any(Class.class))).thenReturn(future);

        Task<String> task = new Task<>("taskName", User.local(), Map.of());
        Task<String> errorTaskInTemporal = new Task<>(task.id, task.name, Task.State.ERROR, 0, task.createdAt, 0, null, task.args, null, new TaskError(new RuntimeException("workflow failed")));
        doReturn(task).when(taskRepository).getTask(task.id);
        doReturn(errorTaskInTemporal).when(temporal).getTask(task.id);
        taskManager.startTask(task, new Group(TaskGroupType.Test));

        future.completeExceptionally(new RuntimeException("workflow failed"));

        verify(taskRepository, atLeastOnce()).update(argThat(t -> t.getState() == Task.State.ERROR));
    }
}
