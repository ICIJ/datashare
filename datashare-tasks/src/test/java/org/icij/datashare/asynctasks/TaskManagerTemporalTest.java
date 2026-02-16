package org.icij.datashare.asynctasks;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.asynctasks.Task.State.ERROR;
import static org.icij.datashare.asynctasks.TaskManagerTemporal.DEFAULT_NAMESPACE;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.icij.datashare.tasks.RoutingStrategy;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;


public class TaskManagerTemporalTest {
    private AutoCloseable mocks;
    @Mock
    private WorkflowClient client;
    private TaskManagerTemporal taskManager;
    @Mock
    private WorkflowServiceGrpc.WorkflowServiceBlockingStub workflowServiceBlockingStub;


    @Before
    public void setUp() {
        mocks = openMocks(this);
        when(client.getOptions()).thenReturn(
            WorkflowClientOptions.newBuilder().setNamespace(DEFAULT_NAMESPACE).build());
        workflowServiceBlockingStub = mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
        when(workflowServiceBlockingStub.listWorkflowExecutions(any(ListWorkflowExecutionsRequest.class)))
            .thenReturn(ListWorkflowExecutionsResponse.newBuilder().build());

        when(client.newUntypedWorkflowStub(anyString(), any(WorkflowOptions.class)))
            .thenReturn(mock(WorkflowStub.class));
        when(client.newUntypedWorkflowStub(anyString())).thenReturn(mock(WorkflowStub.class));
        taskManager = new TaskManagerTemporal(client, workflowServiceBlockingStub, RoutingStrategy.UNIQUE);
    }

    @After
    public void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    public void test_start_task_with_group_routing() throws Exception {
        TaskGroupType key = TaskGroupType.Test;
        String taskName = "taskName";
        Map<String, Object> args = Map.of("someKey", "fooValue");
        Task<String> task = new Task<>(taskName, User.local(), args);
        try (TaskManagerTemporal groupTaskManager = new TaskManagerTemporal(client, workflowServiceBlockingStub,
            RoutingStrategy.GROUP)) {

            groupTaskManager.startTask(task, new Group(key));

            verify(client).newUntypedWorkflowStub(eq(taskName),
                argThat(o -> o.getTaskQueue().equals("workflows-test")));
        }
    }

    @Test
    public void test_start_task_with_name_routing() throws Exception {
        TaskGroupType key = TaskGroupType.Test;
        String taskName = "taskName";
        Map<String, Object> args = Map.of();
        Task<String> task = new Task<>(taskName, User.local(), args);
        try (TaskManagerTemporal groupTaskManager = new TaskManagerTemporal(client, workflowServiceBlockingStub,
            RoutingStrategy.NAME)) {
            groupTaskManager.startTask(task, new Group(key));

            verify(client).newUntypedWorkflowStub(eq(taskName),
                argThat(o -> o.getTaskQueue().equals("workflows-taskname")));
        }
    }

    @Test
    public void test_get_tasks_with_state_filter() throws IOException {
        TaskFilters filter = TaskFilters.empty().withStates(Set.of(Task.State.CANCELLED));

        taskManager.getTasks(filter).toList();

        String expectedQuery = "( ExecutionStatus= 'Canceled' OR ExecutionStatus= 'Terminated' )";
        verify(workflowServiceBlockingStub).listWorkflowExecutions(argThat(r -> r.getQuery().equals(expectedQuery)));
    }

    @Test
    public void test_get_tasks_with_name_filter() throws IOException {
        TaskFilters filter = TaskFilters.empty().withNames("foo");

        taskManager.getTasks(filter).toList();

        String expectedQuery = "( WorkflowType = 'foo' )";
        verify(workflowServiceBlockingStub).listWorkflowExecutions(argThat(r -> r.getQuery().equals(expectedQuery)));
    }

    @Test
    public void test_get_tasks_with_name_prefix_filter() throws IOException {
        TaskFilters filter = TaskFilters.empty().withNames("foo.*");

        taskManager.getTasks(filter).toList();

        String expectedQuery = "( WorkflowType STARTS_WITH 'foo' )";
        verify(workflowServiceBlockingStub).listWorkflowExecutions(argThat(r -> r.getQuery().equals(expectedQuery)));
    }

    @Test
    public void test_get_tasks_with_unsupported_name_pattern_filter() throws IOException {
        Task<String> foo = new Task<>("foo", User.local(), Map.of("user", User.local()));
        taskManager.startTask(foo);

        TaskFilters filter = TaskFilters.empty().withNames("*.foo");
        assertThat(assertThrows(RuntimeException.class, () -> taskManager.getTasks(filter).toList()).getMessage())
            .startsWith("invalid pattern *.foo with Temporal");
    }

    @Test
    public void test_get_tasks_with_user_filter() throws IOException {
        User fooUser = new User("foo");
        TaskFilters filter = TaskFilters.empty().withUser(fooUser);

        taskManager.getTasks(filter).toList();

        String expectedQuery = "( UserId = 'foo' )";
        verify(workflowServiceBlockingStub).listWorkflowExecutions(argThat(r -> r.getQuery().equals(expectedQuery)));
    }

    @Test
    public void test_get_task_ids_with_state_filter() throws IOException {
        TaskFilters filter = TaskFilters.empty().withStates(Set.of(Task.State.CANCELLED));

        taskManager.getTaskIds(filter).toList();

        String expectedQuery = "( ExecutionStatus= 'Canceled' OR ExecutionStatus= 'Terminated' )";
        verify(workflowServiceBlockingStub).listWorkflowExecutions(argThat(r -> r.getQuery().equals(expectedQuery)));
    }

    @Test
    public void test_get_task_ids_with_name_filter() throws IOException {
        TaskFilters filter = TaskFilters.empty().withNames("foo");

        taskManager.getTaskIds(filter).toList();

        String expectedQuery = "( WorkflowType = 'foo' )";
        verify(workflowServiceBlockingStub).listWorkflowExecutions(argThat(r -> r.getQuery().equals(expectedQuery)));
    }

    @Test
    public void test_get_task_ids_with_name_prefix_filter() throws IOException {
        TaskFilters filter = TaskFilters.empty().withNames("foo.*");

        taskManager.getTaskIds(filter).toList();

        String expectedQuery = "( WorkflowType STARTS_WITH 'foo' )";
        verify(workflowServiceBlockingStub).listWorkflowExecutions(argThat(r -> r.getQuery().equals(expectedQuery)));
    }

    @Test
    public void test_get_task_ids_with_user_filter() throws IOException {
        User fooUser = new User("foo");
        TaskFilters filter = TaskFilters.empty().withUser(fooUser);

        taskManager.getTaskIds(filter).toList();

        String expectedQuery = "( UserId = 'foo' )";
        verify(workflowServiceBlockingStub).listWorkflowExecutions(argThat(r -> r.getQuery().equals(expectedQuery)));
    }

    @Test
    public void test_clear_done_tasks_with_state_filter() throws IOException {
        TaskFilters filters = TaskFilters.empty().withStates(Set.of(ERROR));

        taskManager.clearDoneTasks(filters);

        String expectedQuery = "( ExecutionStatus= 'Failed' OR ExecutionStatus= 'TimedOut' )";
        verify(workflowServiceBlockingStub).listWorkflowExecutions(argThat(r -> r.getQuery().equals(expectedQuery)));
    }

    @Test
    public void test_clear_done_tasks_with_name_filter() throws IOException {
        TaskFilters filters = TaskFilters.empty().withNames("hello_world");

        taskManager.clearDoneTasks(filters);

        String expectedQuery =
            "( ExecutionStatus= 'Canceled' "
                + "OR ExecutionStatus= 'Terminated' "
                + "OR ExecutionStatus= 'Failed' "
                + "OR ExecutionStatus= 'TimedOut' "
                + "OR ExecutionStatus= 'Completed'"
                + " ) "
                + "AND ( WorkflowType = 'hello_world' )";
        verify(workflowServiceBlockingStub).listWorkflowExecutions(argThat(r -> r.getQuery().equals(expectedQuery)));
    }

    @Test
    public void test_clear_done_tasks_with_user_filter() throws IOException {
        TaskFilters filter = TaskFilters.empty().withUser(new User("foo"));

        taskManager.clearDoneTasks(filter);

        String expectedQuery =
            "( ExecutionStatus= 'Canceled' "
                + "OR ExecutionStatus= 'Terminated' "
                + "OR ExecutionStatus= 'Failed' "
                + "OR ExecutionStatus= 'TimedOut' "
                + "OR ExecutionStatus= 'Completed'"
                + " ) "
                + "AND ( UserId = 'foo' )";
        verify(workflowServiceBlockingStub).listWorkflowExecutions(argThat(r -> r.getQuery().equals(expectedQuery)));
    }

    @Test
    public void test_stop_unknown_task() {
        when(client.newUntypedWorkflowStub(anyString()).describe())
            .thenThrow(new WorkflowNotFoundException(WorkflowExecution.newBuilder().build(), null, null));
        assertThat(assertThrows(UnknownTask.class, () -> taskManager.stopTask("unknown-id")));
    }
}