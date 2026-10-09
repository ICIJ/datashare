package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.http.security.User;
import net.codestory.http.security.Users;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.asynctasks.TaskManagerMemory;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.CasbinRuleAdapter;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.policies.Role;
import org.icij.datashare.policies.TaskPolicyAnnotation;
import org.icij.datashare.policies.TaskPolicyChecker;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.tasks.TaskFinder;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.tasks.TestSleepingTask;
import org.icij.datashare.tasks.TestTaskUtils;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.TASK_MANAGER_POLLING_INTERVAL_OPT;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.localUser;
import static org.mockito.Mockito.mock;

public class McpStopTaskTest extends AbstractProdWebServerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final TestTaskUtils.DatashareTaskFactoryForTest taskFactory = mock(TestTaskUtils.DatashareTaskFactoryForTest.class);
    private final TaskManagerMemory taskManager = new TaskManagerMemory(
            taskFactory, new TaskRepositoryMemory(),
            new PropertiesProvider(Map.of(TASK_MANAGER_POLLING_INTERVAL_OPT, "500")), new CountDownLatch(1));
    private Authorizer authorizer;

    @Before
    public void setUp() throws Exception {
        serve(Mode.SERVER.name());
    }

    private void serve(String mode) throws Exception {
        TestTaskUtils.init(taskFactory);
        authorizer = new Authorizer(mock(CasbinRuleAdapter.class));
        authorizer.addRoleForUserInProject(localUser("cecile"), Role.PROJECT_ADMIN, Domain.DEFAULT, project("foo"));
        authorizer.addRoleForUserInProject(localUser("john"), Role.PROJECT_MEMBER, Domain.DEFAULT, project("foo"));
        authorizer.addRoleForUserInProject(localUser("jane"), Role.PROJECT_MEMBER, Domain.DEFAULT, project("foo"));
        TaskPolicyChecker taskPolicyChecker =
                Mode.SERVER.name().equals(mode) ? new TaskPolicyAnnotation(authorizer, taskManager)
                                                : TaskPolicyChecker.ALLOW_ALL;
        TaskResource taskResource = new TaskResource(taskFactory, taskManager, new PropertiesProvider(),
                                                     mock(BatchSearchRepository.class),
                                                     new TaskFinder(taskManager, mock(BatchSearchRepository.class)));
        McpResource mcp = new McpResource(Set.of(new McpStopTaskTool(taskResource, taskManager, taskPolicyChecker)));
        Users users = new Users() {
            @Override
            public User find(String login, String password) {
                return find(login);
            }

            @Override
            public User find(String login) {
                return new DatashareUser(localUser(login, "foo"));
            }
        };
        configure(routes -> routes.add(mcp).filter(new BasicAuthFilter("/", "icij", users)));
    }

    @After
    public void tearDown() throws Exception {
        taskManager.clear();
    }

    private String johnsTask() throws Exception {
        return taskManager.startTask(TestSleepingTask.class, localUser("john"), new HashMap<>() {{
            put("defaultProject", "foo");
        }});
    }

    private void waitForState(String taskId, Task.State state) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (taskManager.getTask(taskId).getState() != state && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(taskManager.getTask(taskId).getState()).isEqualTo(state);
    }

    private JsonNode stop(String login, String taskId) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"stop_task\","
                      + "\"arguments\":{\"taskId\":\"" + taskId + "\"}}}";
        return MAPPER.readTree(post("/api/mcp", body).withPreemptiveAuthentication(login, "").response().content());
    }

    @Test
    public void test_project_admin_can_stop_any_task() throws Exception {
        String taskId = johnsTask();
        waitForState(taskId, Task.State.RUNNING);
        JsonNode response = stop("cecile", taskId);
        assertThat(response.at("/result/isError").asBoolean()).as(response.toString()).isFalse();
        assertThat(MAPPER.readTree(response.at("/result/content/0/text").asText()).get("taskId").asText()).isEqualTo(taskId);
        waitForState(taskId, Task.State.CANCELLED);
    }

    @Test
    public void test_local_mode_stops_without_policy_check() throws Exception {
        serve(Mode.LOCAL.name());
        String taskId = johnsTask();
        waitForState(taskId, Task.State.RUNNING);
        JsonNode response = stop("nobody", taskId);
        assertThat(response.at("/result/isError").asBoolean()).as(response.toString()).isFalse();
        waitForState(taskId, Task.State.CANCELLED);
    }

    @Test
    public void test_owner_with_project_member_can_stop_own_task() throws Exception {
        String taskId = johnsTask();
        assertThat(stop("john", taskId).at("/result/isError").asBoolean()).isFalse();
    }

    @Test
    public void test_non_owner_project_member_is_forbidden() throws Exception {
        String taskId = johnsTask();
        waitForState(taskId, Task.State.RUNNING);
        JsonNode response = stop("jane", taskId);
        assertThat(response.at("/result/isError").asBoolean()).isTrue();
        assertThat(response.at("/result/content/0/text").asText()).isEqualTo("forbidden: " + taskId);
        Thread.sleep(300);
        assertThat(taskManager.getTask(taskId).getState()).isEqualTo(Task.State.RUNNING);
    }

    @Test
    public void test_unknown_task_is_not_found() throws Exception {
        JsonNode response = stop("cecile", "nope");
        assertThat(response.at("/result/content/0/text").asText()).isEqualTo("not found: nope");
    }
}
