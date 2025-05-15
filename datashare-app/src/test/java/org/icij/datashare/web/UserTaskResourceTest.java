package org.icij.datashare.web;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import net.codestory.http.filters.basic.BasicAuthFilter;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.tasks.*;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;
import static org.icij.datashare.user.User.localUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class UserTaskResourceTest extends AbstractProdWebServerTest {
    private TaskManagerMemory taskManager;

    @Mock
    BatchSearchRepository batchSearchRepository;

    @Before
    public void setUp() {
        initMocks(this);
        when(batchSearchRepository.getRecords(any(), any())).thenReturn(new ArrayList<>());
    }


    @After
    public void tearDown() throws IOException {
        taskManager.waitTasksToBeDone(1, SECONDS);
        taskManager.clearDoneTasks();
    }

    @Test
    public void test_get_task_unknown_task() {
        setupAppWith(new DummyUserTask<>("foo"), "foo");
        get("/api/task/unknown").withPreemptiveAuthentication("foo", "qux").should().respond(404);
    }

    @Test
    public void test_get_task() throws Exception {
        setupAppWith(new DummyUserTask<>("foo"), "foo");
        String tId = taskManager.startTask(DummyUserTask.class, localUser("foo"), Map.of());
        get("/api/task/" + tId).withPreemptiveAuthentication("foo", "qux").should().respond(200).
                contain(format("{\"@type\":\"Task\",\"id\":\"%s\",\"name\":\"%s\",\"state\":\"DONE\",\"progress\":1.0",tId, DummyUserTask.class.getName())).
                contain("\"details\":").
                contain("\"uid\":\"foo\"").
                contain("\"groups_by_applications\":{\"datashare\":[\"foo-datashare\"]}").
                contain("\"args\":{\"user\":{\"id\":\"foo\"");
    }

    @Test
    public void test_get_task_result_forbidden() throws IOException {
        setupAppWith(new DummyUserTask<>("bar"), "bar", "foo");
        String tId = taskManager.startTask(DummyUserTask.class,localUser("bar"), Map.of());
        get("/api/task/" + tId + "/result").withPreemptiveAuthentication("foo", "qux").should().respond(403);
    }

    @Test
    public void test_get_task_result_unknown_task() throws IOException {
        setupAppWith(new DummyUserTask<>("bar"), "foo");
        taskManager.startTask(DummyUserTask.class, localUser("bar"), Map.of());
        get("/api/task/unknown/result").withPreemptiveAuthentication("foo", "qux").should().respond(404);
    }


    @Test
    public void test_get_task_result_for_not_done_task_should_respond_404() throws IOException {
        setupAppWith(new SleepingUserTask("foo"), new SleepingUserTask("bar"), "foo", "bar");
        String tId = taskManager.startTask(DummyUserTask.class, localUser("foo"), new HashMap<>());
        get("/api/task/" + tId + "/result").withPreemptiveAuthentication("foo", "qux").should().respond(404);
    }

    @Test
    public void test_get_task_result_with_int_result() throws IOException {
        setupAppWith(new DummyUserTask<>("foo", () -> 42), "foo");
        String tId = taskManager.startTask(DummyUserTask.class, localUser("foo"), Map.of());
        get("/api/task/" + tId + "/result").withPreemptiveAuthentication("foo", "qux").
                should().respond(200).
                should().haveType("application/json").
                should().contain("42");
    }

    @Test
    public void test_get_task_result_with_file_result_should_relativize_result_with_app_folder() throws Exception {
        File file = new File(ClassLoader.getSystemResource("app/index.html").toURI());
        UriResult indexHtml = new UriResult(file.toURI(), Files.size(file.toPath()));
        setupAppWith(new DummyUserTask<>("foo", () -> indexHtml), "foo");

        String tId = taskManager.startTask(DummyUserTask.class, localUser("foo"), Map.of());
        taskManager.awaitTermination(1, SECONDS);
        get("/api/task/" + tId + "/result").withPreemptiveAuthentication("foo", "qux").
                should().respond(200).
                should().haveType("application/octet-stream").
                should().haveHeader("Content-Disposition", "attachment;filename=\"index.html\"").
                should().contain("datashare-client");
    }

    @Test
    public void test_get_task_result_with_file_result_and_absolute_path_should_relativize_result_with_app_folder() throws Exception {
        File file = new File(ClassLoader.getSystemResource("app/index.html").toURI());
        UriResult indexHtml = new UriResult(file.toURI(), Files.size(file.toPath()));
        setupAppWith(new DummyUserTask<>("foo", () -> indexHtml), "foo");
        String tId = taskManager.startTask(DummyUserTask.class, localUser("foo"), Map.of());

        get("/api/task/" + tId + "/result").withPreemptiveAuthentication("foo", "qux").should().respond(200);
    }

    @Test
    public void test_get_task_result_when_task_threw_exception_should_show_error()
        throws IOException, InterruptedException {
        setupAppWith(new DummyUserTask<>("foo", () -> {throw new RuntimeException("error blah");}), "foo");
        String tId = taskManager.startTask(DummyUserTask.class, localUser("foo"), Map.of());
        taskManager.awaitTermination(1, SECONDS);

        get("/api/task/" + tId + "/result").withPreemptiveAuthentication("foo", "qux").should().respond(404);
        get("/api/task/" + tId).withPreemptiveAuthentication("foo", "qux").should().contain("error blah");
    }

    @Test
    public void test_task_list_by_name() throws IOException {
        setupAppWith(new DummyUserTask<>("bar"), "bar");
        String t2Id = taskManager.startTask(DummyUserTask.class, localUser("bar"), Map.of());

        get("/api/task/all?name=DummyUserTask").withPreemptiveAuthentication("bar", "qux").should().
                contain(format("{\"id\":\"%s\",\"name\":\"%s\",\"state\":\"DONE\",\"progress\":1.0",t2Id, DummyUserTask.class.getName())).
                contain("\"details\":").
                contain("\"uid\":\"bar\"").
                contain("\"groups_by_applications\":{\"datashare\":[\"bar-datashare\"]}").
                contain("\"args\":{\"user\":{\"id\":\"bar\",");
    }

    @Test
    public void test_stop_all_in_server_mode() throws InterruptedException, IOException {
        setupAppWith(new SleepingUserTask("foo"), new SleepingUserTask("bar"), "foo", "bar");
        String t1Id = taskManager.startTask(SleepingUserTask.class, localUser("foo"), Map.of());
        String t2Id = taskManager.startTask(SleepingUserTask.class, localUser("bar"), Map.of());
        Thread.sleep(100);

        put("/api/task/stopAll").withPreemptiveAuthentication("foo", "pass").should().not().contain(t2Id);
        put("/api/task/stopAll").withPreemptiveAuthentication("bar", "pass").should().not().contain(t1Id);
    }

    @TaskGroup(TaskGroupType.Test)
    public static class DummyUserTask<V extends Serializable> extends DatashareTask<V> implements UserTask {
        private final String user;
        private final Supplier<V> supplier;
        public DummyUserTask(String user) {this(user, () -> null);}
        public DummyUserTask(String user, Supplier<V> supplier) {
            this.user = user;
            this.supplier = supplier;
        }
        @Override public V runTask() { return supplier.get(); }
        @Override public User getUser() { return new User(user);}
    }

    @TaskGroup(TaskGroupType.Test)
    public static class SleepingUserTask implements UserTask, Callable<String> {
        private final String user;
        public SleepingUserTask(String user) {this.user = user;}
        @Override public String call() throws Exception {
            Thread.sleep(10000);
            return "run";
        }
        @Override public User getUser() { return new User(user);}
    }

    private void setupAppWith(DummyUserTask userTask, String... userLogins) {
        DatashareTaskFactoryForTest taskFactory = mock(DatashareTaskFactoryForTest.class);
        when(taskFactory.createDummyUserTask(any(), any())).thenReturn(userTask);
        setupAppWith(taskFactory, userLogins);
    }

    private void setupAppWith(SleepingUserTask c1, SleepingUserTask c2, String... userLogins) {
        DatashareTaskFactoryForTest taskFactory = mock(DatashareTaskFactoryForTest.class);
        when(taskFactory.createSleepingUserTask(any(), any())).thenReturn(c1, c2);
        setupAppWith(taskFactory, userLogins);
    }

    private void setupAppWith(DatashareTaskFactory taskFactory, String... userLogins) {
        final PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of("mode", "LOCAL"));
        taskManager = new TaskManagerMemory(taskFactory, new TaskRepositoryMemory(), new PropertiesProvider());
        configure(routes -> routes.add(new TaskResource(taskFactory, taskManager, propertiesProvider, batchSearchRepository, MAPPER))
                .filter(new BasicAuthFilter("/", "ds", DatashareUser.users(userLogins))));
    }

    public interface DatashareTaskFactoryForTest extends DatashareTaskFactory {
        <V extends Serializable> DummyUserTask<V> createDummyUserTask(Task tv, Function<Double, Void> updateCallback);
        SleepingUserTask createSleepingUserTask(Task tv, Function<Double, Void> updateCallback);
    }
}
