package org.icij.datashare.policies;

import net.codestory.http.Context;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.tasks.TaskManagerMemory;
import org.icij.datashare.tasks.TestSleepingTask;
import org.icij.datashare.tasks.TestTaskUtils;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Map;

import static junit.framework.TestCase.assertEquals;
import static org.icij.datashare.cli.DatashareCliOptions.TASK_MANAGER_POLLING_INTERVAL_OPT;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

public class TaskPolicyAnnotationTest {
    private static final TestTaskUtils.DatashareTaskFactoryForTest taskFactory = mock(TestTaskUtils.DatashareTaskFactoryForTest.class);
    private static final TaskManagerMemory taskManager = new TaskManagerMemory(taskFactory, new TaskRepositoryMemory(), new PropertiesProvider(Map.of(TASK_MANAGER_POLLING_INTERVAL_OPT, "500")));
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
        public boolean allowOwner() {
            return true;
        }

        @Override
        public String idParam() {
            return "taskName:";
        }

    };
    @Mock
    CasbinRuleAdapter jooqCasbinRuleRepository;
    Authorizer authorizer;
    private DatashareUser adminUser;
    private DatashareUser nonAdminUser;
    private String taskId = "test-task";
    private String projectId = "test-datashare";
    private TaskPolicyAnnotation annotation;

    @Before
    public void setUp() {
        mocks = openMocks(this);
        adminUser = new DatashareUser("cecile");
        nonAdminUser = new DatashareUser("john");
        authorizer = new Authorizer(jooqCasbinRuleRepository);
        authorizer.addRoleForUserInProject("cecile", Role.PROJECT_ADMIN, Domain.DEFAULT, projectId);
        authorizer.addRoleForUserInProject("john", Role.PROJECT_MEMBER, Domain.DEFAULT, projectId);
        annotation = new TaskPolicyAnnotation(authorizer, taskManager);
    }

    @After
    public void tearDown() throws IOException {
        taskManager.stopTasks(User.local());
        taskManager.clear();
    }

    @Test(expected = UnauthorizedException.class)
    public void should_throw_unauthorized_if_no_user() {
        Context context = mock(Context.class);
        when(context.pathParam("taskName:")).thenReturn(taskId);
        annotation.apply(adminTaskPolicy, context, (c) -> Payload.ok());
    }

    @Test
    public void should_return_notfound_if_task_not_found() {
        Context context = mock(Context.class);

        DatashareUser noPolicyUser = new DatashareUser("jane");
        when(context.currentUser()).thenReturn(noPolicyUser);

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

}