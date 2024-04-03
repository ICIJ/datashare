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
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.icij.datashare.user.User.localUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class UserTaskResourceTest extends AbstractProdWebServerTest {
    private TaskManagerMemory taskManager;

    @After
    public void tearDown() {
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
        TaskView<String> t = taskManager.startTask(DummyUserTask.class.getName(), localUser("foo"), new HashMap<>());
        get("/api/task/" + t.id).withPreemptiveAuthentication("foo", "qux").should().respond(200).
                contain(format("{\"id\":\"%s\",\"name\":\"%s\",\"state\":\"DONE\",\"progress\":1.0,\"user\":{\"id\":\"foo\",\"name\":null,\"email\":null,\"provider\":\"local\",\"details\":{\"uid\":\"foo\",\"groups_by_applications\":{\"datashare\":[\"foo-datashare\"]}}},\"properties\":{}}", t.id, t.name));
    }

    @Test
    public void test_get_task_result_forbidden() throws IOException {
        setupAppWith(new DummyUserTask<>("bar"), "bar", "foo");
        TaskView<String> t = taskManager.startTask(DummyUserTask.class.getName(),localUser("bar"), new HashMap<>());
        get("/api/task/" + t.id + "/result").withPreemptiveAuthentication("foo", "qux").should().respond(403);
    }

    @Test
    public void test_get_task_result_unknown_task() throws IOException {
        setupAppWith(new DummyUserTask<>("bar"), "foo");
        TaskView<String> t = taskManager.startTask(DummyUserTask.class.getName(), localUser("bar"), new HashMap<>());
        get("/api/task/unknown/result").withPreemptiveAuthentication("foo", "qux").should().respond(404);
    }

    @Test
    public void test_get_task_result_with_no_result() throws IOException {
        setupAppWith(new DummyUserTask<>("foo"), "foo");
        TaskView<String> t = taskManager.startTask(DummyUserTask.class.getName(), localUser("foo"), new HashMap<>());
        get("/api/task/" + t.id + "/result").withPreemptiveAuthentication("foo", "qux").should().respond(204);
    }

    @Test
    public void test_get_task_result_with_int_result() throws IOException {
        setupAppWith(new DummyUserTask<>("foo", () -> 42), "foo");
        TaskView<Integer> t = taskManager.startTask(DummyUserTask.class.getName(), localUser("foo"), new HashMap<>());
        get("/api/task/" + t.id + "/result").withPreemptiveAuthentication("foo", "qux").
                should().respond(200).
                should().haveType("application/json").
                should().contain("42");
    }

    @Test
    public void test_get_task_result_with_file_result_should_relativize_result_with_app_folder() throws Exception {
        File file = new File(ClassLoader.getSystemResource("app/index.html").toURI());
        UriResult indexHtml = new UriResult(file.toURI(), Files.size(file.toPath()));
        setupAppWith(new DummyUserTask<>("foo", () -> indexHtml), "foo");

        TaskView<UriResult> t = taskManager.startTask(DummyUserTask.class.getName(), localUser("foo"), new HashMap<>());
        get("/api/task/" + t.id + "/result").withPreemptiveAuthentication("foo", "qux").
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
        TaskView<UriResult> t = taskManager.startTask(DummyUserTask.class.getName(), localUser("foo"), new HashMap<>());

        get("/api/task/" + t.id + "/result").withPreemptiveAuthentication("foo", "qux").should().respond(200);
    }

    @Test
    public void test_get_task_result_when_task_threw_exception__should_show_error() throws IOException {
        setupAppWith(new DummyUserTask<>("foo", () -> {throw new RuntimeException("error blah");}), "foo");
        TaskView<File> t = taskManager.startTask(DummyUserTask.class.getName(), localUser("foo"), new HashMap<>());

        get("/api/task/" + t.id + "/result").withPreemptiveAuthentication("foo", "qux").should().respond(204);
        get("/api/task/" + t.id).withPreemptiveAuthentication("foo", "qux").should().contain("error blah");
    }

    @Test
    public void test_task_list_with_filter() throws IOException {
        setupAppWith(new DummyUserTask<>("bar"), "bar");
        TaskView<String> t2 = taskManager.startTask(DummyUserTask.class.getName(), localUser("bar"), new HashMap<>());

        get("/api/task/all?filter=DummyUserTask").withPreemptiveAuthentication("bar", "qux").should().contain(format("{\"id\":\"%s\",\"name\":\"%s\",\"state\":\"DONE\",\"progress\":1.0,\"user\":{\"id\":\"bar\",\"name\":null,\"email\":null,\"provider\":\"local\",\"details\":{\"uid\":\"bar\",\"groups_by_applications\":{\"datashare\":[\"bar-datashare\"]}}},\"properties\":{}}", t2.id, t2.name));
        get("/api/task/all?filter=foo").withPreemptiveAuthentication("bar", "qux").should().contain("[]");
    }

    @Test
    public void test_stop_all_in_server_mode() throws InterruptedException, IOException {
        setupAppWith(new SleepingUserTask("foo"), new SleepingUserTask("bar"), "foo", "bar");
        TaskView<String> t1 = taskManager.startTask(SleepingUserTask.class.getName(), localUser("foo"), new HashMap<>());
        TaskView<String> t2 = taskManager.startTask(SleepingUserTask.class.getName(), localUser("bar"), new HashMap<>());
        Thread.sleep(100);

        put("/api/task/stopAll").withPreemptiveAuthentication("foo", "pass").should().not().contain(t2.id);
        put("/api/task/stopAll").withPreemptiveAuthentication("bar", "pass").should().not().contain(t1.id);
    }

    public static class DummyUserTask<V> implements UserTask, Callable<V> {
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

    public static class SleepingUserTask implements UserTask, CancellableCallable<String> {
        private final String user;
        private Thread callThread;
        public SleepingUserTask(String user) {this.user = user;}
        @Override public String call() throws Exception {
            callThread = Thread.currentThread();
            Thread.sleep(10000);
            return "run";
        }
        @Override public User getUser() { return new User(user);}

        @Override
        public void cancel(String taskId, boolean requeue) {
            ofNullable(callThread).ifPresent(Thread::interrupt);
        }
    }

    private void setupAppWith(DummyUserTask<?> userTask, String... userLogins) {
        TaskFactoryForTest taskFactory = mock(TaskFactoryForTest.class);
        when(taskFactory.createDummyUserTask(any(), any())).thenReturn((DummyUserTask<Object>) userTask);
        setupAppWith(taskFactory, userLogins);
    }
    private void setupAppWith(SleepingUserTask c1, SleepingUserTask c2, String... userLogins) {
        TaskFactoryForTest taskFactory = mock(TaskFactoryForTest.class);
        when(taskFactory.createSleepingUserTask(any(), any())).thenReturn(c1, c2);
        setupAppWith(taskFactory, userLogins);
    }
    private void setupAppWith(TaskFactory taskFactory, String... userLogins) {
        final PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
            put("mode", "LOCAL");
        }});

        taskManager = new TaskManagerMemory(new ArrayBlockingQueue<>(3), taskFactory);
        configure(new CommonMode(propertiesProvider.getProperties()) {
            @Override
            protected void configure() {
                bind(PropertiesProvider.class).toInstance(propertiesProvider);
                bind(PipelineRegistry.class).toInstance(mock(PipelineRegistry.class));
                bind(SessionIdStore.class).toInstance(SessionIdStore.inMemory());
                bind(TaskManager.class).toInstance(taskManager);
                bind(TaskModifier.class).toInstance(taskManager);
                bind(Filter.class).toInstance(new BasicAuthFilter("/", "ds", DatashareUser.users(userLogins)));
                bind(TaskFactory.class).toInstance(taskFactory);
                bind(Indexer.class).toInstance(mock(Indexer.class));
            }
            @Override protected Routes addModeConfiguration(Routes routes) { return routes.add(TaskResource.class).filter(Filter.class);}
        }.createWebConfiguration());
    }

    public interface TaskFactoryForTest extends TaskFactory {
        <V> DummyUserTask<V> createDummyUserTask(TaskView<V> tv, BiFunction<String, Integer, Void> updateCallback);
        SleepingUserTask createSleepingUserTask(TaskView<?> tv, BiFunction<String, Integer, Void> updateCallback);
    }
}
