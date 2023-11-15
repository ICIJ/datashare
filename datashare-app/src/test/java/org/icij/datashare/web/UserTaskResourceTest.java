package org.icij.datashare.web;

import net.codestory.http.filters.Filter;
import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.http.routes.Routes;
import net.codestory.http.security.SessionIdStore;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.tasks.*;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.mockito.Mockito.mock;

public class UserTaskResourceTest extends AbstractProdWebServerTest {
    private TaskManagerMemory taskManager;

    @After
    public void tearDown() {
        taskManager.waitTasksToBeDone(1, SECONDS);
        taskManager.clearDoneTasks();
    }

    @Test
    public void test_get_task_unknown_task() {
        setupAppWith("foo");
        get("/api/task/unknown").withPreemptiveAuthentication("foo", "qux").should().respond(404);
    }

    @Test
    public void test_get_task() throws Exception {
        setupAppWith("foo");
        TaskView<String> t = taskManager.startTask(new DummyUserTask<>("foo"));
        get("/api/task/" + t.id).withPreemptiveAuthentication("foo", "qux").should().respond(200).
            contain(format("{\"id\":\"%s\",\"state\":\"DONE\",\"progress\":1.0,\"user\":{\"id\":\"foo\",\"name\":null,\"email\":null,\"provider\":\"local\",\"details\":{}}}", t.id));
    }

    @Test
    public void test_get_task_result_forbidden() {
        setupAppWith("bar", "foo");
        TaskView<String> t = taskManager.startTask(new DummyUserTask<>("bar"));
        get("/api/task/" + t.id + "/result").withPreemptiveAuthentication("foo", "qux").should().respond(403);
    }

    @Test
    public void test_get_task_result_unknown_task() {
        setupAppWith("foo");
        TaskView<String> t = taskManager.startTask(new DummyUserTask<>("bar"));
        get("/api/task/unknown/result").withPreemptiveAuthentication("foo", "qux").should().respond(404);
    }

    @Test
    public void test_get_task_result_with_no_result() {
        setupAppWith("foo");
        TaskView<String> t = taskManager.startTask(new DummyUserTask<>("foo"));
        get("/api/task/" + t.id + "/result").withPreemptiveAuthentication("foo", "qux").should().respond(204);
    }

    @Test
    public void test_get_task_result_with_int_result() {
        setupAppWith("foo");
        TaskView<Integer> t = taskManager.startTask(new DummyUserTask<>("foo", () -> 42));
        get("/api/task/" + t.id + "/result").withPreemptiveAuthentication("foo", "qux").
                should().respond(200).
                should().haveType("application/json").
                should().contain("42");
    }

    @Test
    public void test_get_task_result_with_file_result__should_relativize_result_with_app_folder() {
        setupAppWith("foo");
        TaskView<File> t = taskManager.startTask(new DummyUserTask<>("foo", () -> Paths.get("app", "index.html").toFile()));
        get("/api/task/" + t.id + "/result").withPreemptiveAuthentication("foo", "qux").
                should().respond(200).
                should().haveType("text/html;charset=UTF-8").
                should().haveHeader("Content-Disposition", "attachment;filename=\"index.html\"").
                should().contain("datashare-client");
    }

    @Test
    public void test_get_task_result_with_file_result_and_absolute_path__should_relativize_result_with_app_folder() throws Exception {
        setupAppWith("foo");
        File indexHtml = new File(ClassLoader.getSystemResource("app/index.html").toURI());
        TaskView<File> t = taskManager.startTask(new DummyUserTask<>("foo", () -> indexHtml));

        get("/api/task/" + t.id + "/result").withPreemptiveAuthentication("foo", "qux").should().respond(200);
    }

    @Test
    public void test_get_task_result_when_task_threw_exception__should_show_error() throws Exception {
        setupAppWith("foo");
        TaskView<File> t = taskManager.startTask(new DummyUserTask<>("foo", () -> {throw new RuntimeException("error blah");}));

        get("/api/task/" + t.id + "/result").withPreemptiveAuthentication("foo", "qux").should().respond(204);
        get("/api/task/" + t.id).withPreemptiveAuthentication("foo", "qux").should().contain("error blah");
    }

    @Test
    public void test_task_list_in_server_mode() {
        setupAppWith("foo", "bar");
        TaskView<String> t1 = taskManager.startTask(new DummyUserTask<>("foo"));
        TaskView<String> t2 = taskManager.startTask(new DummyUserTask<>("bar"));

        System.out.println(t1.properties);
        get("/api/task/all").withPreemptiveAuthentication("foo", "qux").should().contain(format("[{\"id\":\"%s\",\"state\":\"DONE\",\"progress\":1.0,\"user\":{\"id\":\"foo\",\"name\":null,\"email\":null,\"provider\":\"local\",\"details\":{}}}]", t1.id));
        get("/api/task/all").withPreemptiveAuthentication("bar", "qux").should().contain(format("[{\"id\":\"%s\",\"state\":\"DONE\",\"progress\":1.0,\"user\":{\"id\":\"bar\",\"name\":null,\"email\":null,\"provider\":\"local\",\"details\":{}}}]", t2.id));
    }

    @Test
    public void test_task_list_with_filter() {
        setupAppWith("bar");
        TaskView<String> t2 = taskManager.startTask(new DummyUserTask<>("bar"));

        get("/api/task/all?filter=DummyUserTask").withPreemptiveAuthentication("bar", "qux").should().contain(format("[{\"id\":\"%s\",\"state\":\"DONE\",\"progress\":1.0,\"user\":{\"id\":\"bar\",\"name\":null,\"email\":null,\"provider\":\"local\",\"details\":{}}}]", t2.id));
        get("/api/task/all?filter=foo").withPreemptiveAuthentication("bar", "qux").should().contain("[]");
    }

    @Test
    public void test_stop_all_in_server_mode() {
        setupAppWith("foo", "bar");
        TaskView<String> t1 = taskManager.startTask(new SleepingUserTask("foo"));
        TaskView<String> t2 = taskManager.startTask(new SleepingUserTask("bar"));

        put("/api/task/stopAll").withPreemptiveAuthentication("foo", "pass").should().not().contain(t2.id);
        put("/api/task/stopAll").withPreemptiveAuthentication("bar", "pass").should().not().contain(t1.id);
    }

    static class DummyUserTask<V> implements UserTask, Callable<V> {
        private final String user;
        private final Supplier<V> supplier;
        public DummyUserTask(String user) {this(user, () -> null);}
        public DummyUserTask(String user, Supplier<V> supplier) {
            this.user = user;
            this.supplier = supplier;
        }
        @Override public V call() throws Exception { return supplier.get(); }
        @Override public User getUser() { return new User(user);}
    }

    static class SleepingUserTask implements UserTask, Callable<String> {
        private final String user;
        public SleepingUserTask(String user) {this.user = user;}
        @Override public String call() throws Exception {Thread.sleep(10000);return "run";}
        @Override public User getUser() { return new User(user);}
    }

    private void setupAppWith(String... userLogins) {
        final PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
            put("mode", "LOCAL");
        }});
        taskManager = new TaskManagerMemory(propertiesProvider);
        configure(new CommonMode(propertiesProvider.getProperties()) {
            @Override
            protected void configure() {
                bind(PropertiesProvider.class).toInstance(propertiesProvider);
                bind(PipelineRegistry.class).toInstance(mock(PipelineRegistry.class));
                bind(SessionIdStore.class).toInstance(SessionIdStore.inMemory());
                bind(TaskManager.class).toInstance(taskManager);
                bind(TaskModifier.class).toInstance(taskManager);
                bind(Filter.class).toInstance(new BasicAuthFilter("/", "ds", DatashareUser.users(userLogins)));
                bind(TaskFactory.class).toInstance(mock(TaskFactory.class));
                bind(Indexer.class).toInstance(mock(Indexer.class));
            }
            @Override protected Routes addModeConfiguration(Routes routes) { return routes.add(TaskResource.class).filter(Filter.class);}
        }.createWebConfiguration());
    }
}
