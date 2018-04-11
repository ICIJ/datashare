package org.icij.datashare;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import net.codestory.rest.RestAssert;
import org.icij.task.Options;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.concurrent.Callable;

import static org.mockito.Mockito.*;

public class TaskResourceTest implements FluentRestTest {
    private static WebServer server = new WebServer() {
        @Override
        protected Env createEnv() {
            return Env.prod();
        }
    }.startOnRandomPort();
    private static TaskFactory taskFactory = mock(TaskFactory.class);

    @Override
    public int port() {
        return server.port();
    }

    @BeforeClass
    public static void setUpClass() {
        server.configure(WebApp.getConfiguration(new TestModule()));
    }

    @Before
    public void setUp() { reset(taskFactory);}

    @Test
    public void test_index_file() {
        RestAssert response = post("/task/index/file/" + getClass().getResource("/docs/doc.txt").getPath().replace("/", "%7C"), "{}");

        response.should().haveType("application/json").should().contain(
                "{\"hash\":12,\"state\":\"RUNNING\",\"progress\":-2.0}," +
                        "{\"hash\":12,\"state\":\"RUNNING\",\"progress\":-2.0}");
    }

    @Test
    public void test_index_directory() {
        RestAssert response = post("/task/index/file/" + getClass().getResource("/docs/").getPath().replace("/", "%7C"), "{}");

        response.should().haveType("application/json").should().contain(
                "{\"hash\":12,\"state\":\"RUNNING\",\"progress\":-2.0}," +
                        "{\"hash\":12,\"state\":\"RUNNING\",\"progress\":-2.0}");
    }

    @Test
    public void test_index_and_scan_directory_with_options() {
        String path = getClass().getResource("/docs/").getPath();

        RestAssert response = post("/task/index/file/" + path.replace("/", "%7C"),
                "{\"options\":{\"key1\":\"val1\",\"key2\":\"val2\"}}");

        response.should().haveType("application/json").should().contain("{\"hash\":12,\"state\":\"RUNNING\",\"progress\":-2.0}");
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

        response.should().haveType("application/json").should().contain("{\"hash\":12,\"state\":\"RUNNING\",\"progress\":-2.0}");
        verify(taskFactory).createSpewTask(Options.from(new HashMap<String, String>() {{
            put("key1", "val1");
            put("key2", "val2");
        }}));
        verify(taskFactory, never()).createScanTask(any(Path.class), any(Options.class));
    }

    @Test
    public void test_queue_with_options() {
        String path = getClass().getResource("/docs/").getPath();
        RestAssert response = post("/task/scan/file/" + path.replace("/", "%7C"),
                "{\"options\":{\"key1\":\"val1\",\"key2\":\"val2\"}}");

        response.should().haveType("application/json").should().contain("{\"hash\":12,\"state\":\"RUNNING\",\"progress\":-2.0}");
        verify(taskFactory).createScanTask(Paths.get(path), Options.from(new HashMap<String, String>() {{
            put("key1", "val1");
            put("key2", "val2");
        }}));
        verify(taskFactory, never()).createSpewTask(any(Options.class));
    }

    static class TestModule extends AbstractModule {
        @Override protected void configure() {
            bind(TaskFactory.class).toInstance(taskFactory);
            bind(TaskManager.class).to(DummyTaskManager.class);
        }
    }

    static class DummyTaskManager extends TaskManager {
        @Inject
        public DummyTaskManager(PropertiesProvider provider) {
            super(provider);
        }

        @Override public <V> MonitorableFutureTask<V> startTask(Callable<V> task) {
            return new MonitorableFutureTask<V>(() -> null) {
                @Override public int hashCode() {
                    return 12;
                }
            };
        }
    }
}
