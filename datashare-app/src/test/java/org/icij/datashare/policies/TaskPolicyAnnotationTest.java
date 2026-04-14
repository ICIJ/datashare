package org.icij.datashare.policies;

import net.codestory.http.Context;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.asynctasks.TaskManagerMemory;
import java.util.concurrent.CountDownLatch;
import org.icij.datashare.tasks.TestSleepingTask;
import org.icij.datashare.tasks.TestTaskUtils;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.ProjectProxy;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static junit.framework.TestCase.assertEquals;
import static org.icij.datashare.cli.DatashareCliOptions.TASK_MANAGER_POLLING_INTERVAL_OPT;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.localUser;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

public class TaskPolicyAnnotationTest {
    private static final TestTaskUtils.DatashareTaskFactoryForTest taskFactory = mock(TestTaskUtils.DatashareTaskFactoryForTest.class);
    private static final TaskManagerMemory taskManager = new TaskManagerMemory(taskFactory, new TaskRepositoryMemory(), new PropertiesProvider(Map.of(TASK_MANAGER_POLLING_INTERVAL_OPT, "500")), new CountDownLatch(1));
    private static AutoCloseable mocks;
    private final TaskPolicy adminTaskPolicy = new TaskPolicy() {
        @Override
        public Class<? extends Annotation> annotationType() {
            return TaskPolicy.class;
        }

        @Override
        public Role role() {
            return Role.PROJECT_ADMIN;
        }

        @Override
        public Role ownerRole() {
            return Role.PROJECT_MEMBER;
        }

        @Override
        public String idParam() {
            return "taskName:";
        }

        @Override
        public boolean singleTask() {
            return true;
        }

        @Override
        public String domain() {
            return "default";
        }

    };
    private final TaskPolicy noOwnerRoleTaskPolicy = new TaskPolicy() {
        @Override
        public Class<? extends Annotation> annotationType() {
            return TaskPolicy.class;
        }

        @Override
        public Role role() {
            return Role.PROJECT_ADMIN;
        }

        @Override
        public Role ownerRole() {
            return Role.NONE;
        }

        @Override
        public String idParam() {
            return "taskName:";
        }

        @Override
        public boolean singleTask() {
            return true;
        }

        @Override
        public String domain() {
            return "default";
        }

    };

    private final TaskPolicy batchTaskPolicy = new TaskPolicy() {
        @Override
        public Class<? extends Annotation> annotationType() {
            return TaskPolicy.class;
        }

        @Override
        public Role role() {
            return Role.DOMAIN_ADMIN;
        }

        @Override
        public Role ownerRole() {
            return Role.NONE;
        }

        @Override
        public String idParam() {
            return "taskName:";
        }

        @Override
        public boolean singleTask() {
            return false;
        }

        @Override
        public String domain() {
            return "default";
        }

    };

    private final String projectId = "test-datashare";
    Authorizer authorizer;
    @Mock
    CasbinRuleAdapter adapter;
    private TaskPolicyAnnotation annotation;

    @Before
    public void setUp() throws IOException {
        mocks = openMocks(this);
        authorizer = new Authorizer(adapter);
        User cecile = localUser("cecile");
        User john = localUser("john");
        Project project = project(projectId);
        authorizer.addRoleForUserInProject(cecile, Role.PROJECT_ADMIN, Domain.DEFAULT, project);
        authorizer.addRoleForUserInProject(john, Role.PROJECT_MEMBER, Domain.DEFAULT, project);
        annotation = new TaskPolicyAnnotation(authorizer, taskManager);
    }

    @After
    public void tearDown() throws Exception {
        taskManager.stopTasks(User.local());
        taskManager.clear();
        mocks.close();
    }

    @Test(expected = UnauthorizedException.class)
    public void should_throw_unauthorized_if_no_user() {
        Context context = mock(Context.class);
        annotation.apply(adminTaskPolicy, context, (c) -> Payload.ok());
    }

    @Test
    public void should_return_notfound_if_task_not_found() {
        Context context = mock(Context.class);

        DatashareUser noPolicyUser = new DatashareUser("jane");
        when(context.currentUser()).thenReturn(noPolicyUser);

        String taskId = "test-task";
        when(context.pathParam("taskName:")).thenReturn(taskId);

        Payload result = annotation.apply(adminTaskPolicy, context, c -> Payload.ok());
        assertEquals(404, result.code());
    }

    @Test
    public void should_return_forbidden_if_no_policy() throws IOException {
        Context context = mock(Context.class);
        DatashareUser noPolicyUser = new DatashareUser("jane");
        String dummyTaskId = taskManager.startTask(TestSleepingTask.class, User.local(), new HashMap<>() {{
            put("defaultProject", projectId);
        }});

        when(context.currentUser()).thenReturn(noPolicyUser);

        when(context.pathParam("taskName:")).thenReturn(dummyTaskId);

        Payload result = annotation.apply(adminTaskPolicy, context, c -> Payload.ok());
        assertEquals(403, result.code());
    }

    @Test(expected = IllegalStateException.class)
    public void should_throw_exception_if_task_has_no_project() throws IOException {
        Context context = mock(Context.class);
        DatashareUser noPolicyUser = new DatashareUser("jane");
        String dummyTaskId = taskManager.startTask(TestSleepingTask.class, User.local(), new HashMap<>() {
        });

        when(context.currentUser()).thenReturn(noPolicyUser);

        when(context.pathParam("taskName:")).thenReturn(dummyTaskId);

        annotation.apply(adminTaskPolicy, context, c -> Payload.ok());
    }

    @Test
    public void should_allow_access_when_owner_role_is_none_and_user_has_required_role() throws IOException {
        Context context = mock(Context.class);
        DatashareUser cecile = new DatashareUser("cecile");
        String dummyTaskId = taskManager.startTask(TestSleepingTask.class, User.local(), new HashMap<>() {{
            put("defaultProject", projectId);
        }});

        when(context.currentUser()).thenReturn(cecile);
        when(context.pathParam("taskName:")).thenReturn(dummyTaskId);

        Payload result = annotation.apply(noOwnerRoleTaskPolicy, context, c -> Payload.ok());
        assertEquals(200, result.code());
    }

    @Test
    public void should_return_forbidden_when_owner_role_is_none_and_user_lacks_required_role() throws IOException {
        Context context = mock(Context.class);
        DatashareUser john = new DatashareUser("john");
        String dummyTaskId = taskManager.startTask(TestSleepingTask.class, User.local(), new HashMap<>() {{
            put("defaultProject", projectId);
        }});

        when(context.currentUser()).thenReturn(john);
        when(context.pathParam("taskName:")).thenReturn(dummyTaskId);

        Payload result = annotation.apply(noOwnerRoleTaskPolicy, context, c -> Payload.ok());
        assertEquals(403, result.code());
    }

    @Test
    public void should_allow_task_owner_via_owner_role_even_without_required_role() throws IOException {
        Context context = mock(Context.class);
        DatashareUser john = new DatashareUser("john");
        // john (PROJECT_MEMBER) starts a task — he is the task owner
        String taskId = taskManager.startTask(TestSleepingTask.class, john, new HashMap<>() {{
            put("defaultProject", projectId);
        }});

        when(context.currentUser()).thenReturn(john);
        when(context.pathParam("taskName:")).thenReturn(taskId);

        // adminTaskPolicy requires PROJECT_ADMIN but has ownerRole=PROJECT_MEMBER;
        // john lacks PROJECT_ADMIN but is the owner → must be allowed
        Payload result = annotation.apply(adminTaskPolicy, context, c -> Payload.ok());
        assertEquals(200, result.code());
    }

    @Test
    public void should_allow_access_for_batch_search_when_user_has_role_on_all_projects() throws IOException {
        Context context = mock(Context.class);
        DatashareUser cecile = new DatashareUser("cecile");
        BatchSearchRecord batchRecord = new BatchSearchRecord(
                List.of(new ProjectProxy(projectId)), "test-search", "desc", 1, new Date(), null);
        String taskId = taskManager.startTask(TestSleepingTask.class, User.local(), new HashMap<>() {{
            put("batchRecord", batchRecord);
        }});

        when(context.currentUser()).thenReturn(cecile);
        when(context.pathParam("taskName:")).thenReturn(taskId);

        // cecile is PROJECT_ADMIN on projectId → all projects match
        Payload result = annotation.apply(adminTaskPolicy, context, c -> Payload.ok());
        assertEquals(200, result.code());
    }

    @Test
    public void should_return_forbidden_for_batch_search_when_user_lacks_role_on_some_project() throws IOException {
        Context context = mock(Context.class);
        DatashareUser john = new DatashareUser("john");
        String otherProjectId = "other-project";
        // john has PROJECT_MEMBER on projectId but no role on otherProjectId
        BatchSearchRecord batchRecord = new BatchSearchRecord(
                List.of(new ProjectProxy(projectId), new ProjectProxy(otherProjectId)),
                "test-search", "desc", 1, new Date(), null);
        String taskId = taskManager.startTask(TestSleepingTask.class, User.local(), new HashMap<>() {{
            put("batchRecord", batchRecord);
        }});

        when(context.currentUser()).thenReturn(john);
        when(context.pathParam("taskName:")).thenReturn(taskId);

        // noOwnerRoleTaskPolicy requires PROJECT_ADMIN; john lacks it on both projects
        Payload result = annotation.apply(noOwnerRoleTaskPolicy, context, c -> Payload.ok());
        assertEquals(403, result.code());
    }

    @Test
    public void should_allow_access_for_batch_download_when_user_has_role_on_all_projects() throws IOException {
        Context context = mock(Context.class);
        DatashareUser cecile = new DatashareUser("cecile");
        BatchDownload batchDownload = new BatchDownload(
                List.of(Project.project(projectId)), User.local(), "*");
        String taskId = taskManager.startTask(TestSleepingTask.class, User.local(), new HashMap<>() {{
            put("batchDownload", batchDownload);
        }});

        when(context.currentUser()).thenReturn(cecile);
        when(context.pathParam("taskName:")).thenReturn(taskId);

        // cecile is PROJECT_ADMIN on projectId → all projects match
        Payload result = annotation.apply(adminTaskPolicy, context, c -> Payload.ok());
        assertEquals(200, result.code());
    }

    @Test
    public void should_return_forbidden_for_batch_download_when_user_lacks_role_on_some_project() throws IOException {
        Context context = mock(Context.class);
        DatashareUser john = new DatashareUser("john");
        String otherProjectId = "other-project";
        // john has PROJECT_MEMBER on projectId but no role on otherProjectId
        BatchDownload batchDownload = new BatchDownload(
                List.of(Project.project(projectId), Project.project(otherProjectId)), User.local(), "*");
        String taskId = taskManager.startTask(TestSleepingTask.class, User.local(), new HashMap<>() {{
            put("batchDownload", batchDownload);
        }});

        when(context.currentUser()).thenReturn(john);
        when(context.pathParam("taskName:")).thenReturn(taskId);

        // noOwnerRoleTaskPolicy requires PROJECT_ADMIN; john lacks it on both projects
        Payload result = annotation.apply(noOwnerRoleTaskPolicy, context, c -> Payload.ok());
        assertEquals(403, result.code());
    }

    @Test
    public void should_return_forbidden_without_npe_when_task_has_no_user() throws IOException {
        Context context = mock(Context.class);
        DatashareUser john = new DatashareUser("john");
        // Build a task with null user; getUser() returns null
        String taskId = taskManager.startTask(TestSleepingTask.class.getName(), null,
                Map.of("defaultProject", projectId));

        when(context.currentUser()).thenReturn(john);
        when(context.pathParam("taskName:")).thenReturn(taskId);

        // john has ownerRole (PROJECT_MEMBER) but is not the owner (task.getUser() == null)
        // must return 403, not throw NullPointerException
        Payload result = annotation.apply(adminTaskPolicy, context, c -> Payload.ok());
        assertEquals(403, result.code());
    }

    @Test
    public void should_allow_batch_operation_when_user_has_wildcard_role() {
        Context context = mock(Context.class);
        DatashareUser cecile = new DatashareUser("cecile");
        // Give cecile DOMAIN_ADMIN on the wildcard project (domain-level)
        authorizer.addRoleForUserInDomain(cecile, Role.DOMAIN_ADMIN, Domain.DEFAULT);

        when(context.currentUser()).thenReturn(cecile);

        // batchTaskPolicy (singleTask=false) checks wildcard project access
        Payload result = annotation.apply(batchTaskPolicy, context, c -> Payload.ok());
        assertEquals(200, result.code());
    }

    @Test
    public void should_deny_batch_operation_when_user_has_only_specific_project_role() {
        Context context = mock(Context.class);
        DatashareUser cecile = new DatashareUser("cecile");
        // cecile has PROJECT_ADMIN only on test-datashare (from setUp), not on *

        when(context.currentUser()).thenReturn(cecile);

        // batchTaskPolicy requires wildcard project access; specific project role is insufficient
        Payload result = annotation.apply(batchTaskPolicy, context, c -> Payload.ok());
        assertEquals(403, result.code());
    }

    @Test
    public void should_deny_batch_operation_when_user_has_insufficient_role() {
        Context context = mock(Context.class);
        DatashareUser john = new DatashareUser("john");
        // Give john PROJECT_MEMBER on * — insufficient for PROJECT_ADMIN requirement
        authorizer.addRoleForUserInProject(john, Role.PROJECT_MEMBER, Domain.DEFAULT, project("*"));

        when(context.currentUser()).thenReturn(john);

        // batchTaskPolicy requires PROJECT_ADMIN; john has only PROJECT_MEMBER
        Payload result = annotation.apply(batchTaskPolicy, context, c -> Payload.ok());
        assertEquals(403, result.code());
    }

}