package org.icij.datashare;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import net.codestory.rest.RestAssert;
import net.codestory.rest.ShouldChain;
import org.icij.task.Options;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;

import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class TaskResourceTest implements FluentRestTest {
    private static WebServer server = new WebServer() {
        @Override
        protected Env createEnv() {
            return Env.prod();
        }
    }.startOnRandomPort();
    private static TaskFactory taskFactory = mock(TaskFactory.class);
    private static TaskManager taskManager;
    @Override
    public int port() {
        return server.port();
    }

    @BeforeClass
    public static void setUpClass() {
        Injector injector = Guice.createInjector(new TestModule());
        taskManager = injector.getInstance(TaskManager.class);
        server.configure(WebApp.getConfiguration(injector));
    }

    @Before
    public void setUp() { reset(taskFactory);}

    @After
    public void tearDown() throws Exception {
        taskManager.waitTasksToBeDone(1, SECONDS);
        taskManager.cleanDoneTasks();
    }

    @Test
    public void test_index_file() {
        RestAssert response = post("/task/index/file/" + getClass().getResource("/docs/doc.txt").getPath().replace("/", "%7C"), "{}");

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(Object::toString).collect(toList());
        responseBody.should().contain(format("{\"name\":\"%s\"", taskNames.get(0)));
        responseBody.should().contain(format("{\"name\":\"%s\"", taskNames.get(1)));
    }

    @Test
    public void test_index_directory() {
        RestAssert response = post("/task/index/file/" + getClass().getResource("/docs/").getPath().replace("/", "%7C"), "{}");

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(Object::toString).collect(toList());
        responseBody.should().contain(format("{\"name\":\"%s\"", taskNames.get(0)));
        responseBody.should().contain(format("{\"name\":\"%s\"", taskNames.get(1)));
    }

    @Test
    public void test_index_and_scan_directory_with_options() {
        String path = getClass().getResource("/docs/").getPath();

        RestAssert response = post("/task/index/file/" + path.replace("/", "%7C"),
                "{\"options\":{\"key1\":\"val1\",\"key2\":\"val2\"}}");

        response.should().haveType("application/json");
        verify(taskFactory).createSpewTask(Options.from(new HashMap<String, String>() {{
            put("key1", "val1");
            put("key2", "val2");
        }}));
        verify(taskFactory).createScanTask(Paths.get(path), Options.from(new HashMap<String, String>() {{
            put("key1", "val1");
            put("key2", "val2");
        }}));
    }

    @Test
    public void test_index_queue_with_options() {
        RestAssert response = post("/task/index/", "{\"options\":{\"key1\":\"val1\",\"key2\":\"val2\"}}");

        response.should().haveType("application/json");
        verify(taskFactory).createSpewTask(Options.from(new HashMap<String, String>() {{
            put("key1", "val1");
            put("key2", "val2");
        }}));
        verify(taskFactory, never()).createScanTask(any(Path.class), any(Options.class));
    }

    @Test
    public void test_scan_with_options() {
        String path = getClass().getResource("/docs/").getPath();
        RestAssert response = post("/task/scan/file/" + path.replace("/", "%7C"),
                "{\"options\":{\"key1\":\"val1\",\"key2\":\"val2\"}}");

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(Object::toString).collect(toList());
        assertThat(taskNames.size()).isEqualTo(1);
        responseBody.should().contain(format("{\"name\":\"%s\"", taskNames.get(0)));
        verify(taskFactory).createScanTask(Paths.get(path), Options.from(new HashMap<String, String>() {{
            put("key1", "val1");
            put("key2", "val2");
        }}));
        verify(taskFactory, never()).createSpewTask(any(Options.class));
    }

    @Test
    public void test_clean_tasks() throws Exception {
        post("/task/index/file/" + getClass().getResource("/docs/doc.txt").getPath().replace("/", "%7C"), "{}").response();
        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(Object::toString).collect(toList());

        ShouldChain responseBody = post("/task/clean/", "{}").should().haveType("application/json");

        responseBody.should().contain(taskNames.get(0));
        responseBody.should().contain(taskNames.get(1));
        assertThat(taskManager.getTasks()).isEmpty();
    }

    static class TestModule extends AbstractModule {
        @Override protected void configure() {
            bind(TaskFactory.class).toInstance(taskFactory);
            bind(TaskManager.class).to(DummyTaskManager.class).asEagerSingleton();
        }
    }

    static class DummyTaskManager extends TaskManager {
        @Inject
        public DummyTaskManager(PropertiesProvider provider) {
            super(provider);
        }

        @Override public <V> MonitorableFutureTask<V> startTask(Callable<V> task) {
            return super.startTask(new Callable<V>() {    // do not replace by lambda
                @Override public V call() { return null;} // else tasks will have the same lambda name and test will fail
            });
        }
    }
}
