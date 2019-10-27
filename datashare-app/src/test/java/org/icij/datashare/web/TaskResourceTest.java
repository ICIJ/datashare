package org.icij.datashare.web;

import net.codestory.http.WebServer;
import net.codestory.http.filters.Filter;
import net.codestory.http.misc.Env;
import net.codestory.http.routes.Routes;
import net.codestory.rest.FluentRestTest;
import net.codestory.rest.RestAssert;
import net.codestory.rest.ShouldChain;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.tasks.*;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.AbstractModels;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.NlpApp;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.icij.task.Options;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static java.lang.String.format;
import static java.util.Collections.singleton;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.session.HashMapUser.local;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

public class TaskResourceTest implements FluentRestTest {
    private static WebServer server = new WebServer() {
        @Override
        protected Env createEnv() {
            return Env.prod();
        }
    }.startOnRandomPort();
    private static TaskFactory taskFactory = mock(TaskFactory.class);
    private static TaskManager taskManager= new TaskManager(new PropertiesProvider());
    @Override
    public int port() {
        return server.port();
    }

    @BeforeClass
    public static void setUpClass() {
        server.configure(new CommonMode(new Properties()) {
            @Override
            protected void configure() {
                bind(TaskFactory.class).toInstance(taskFactory);
                bind(Indexer.class).toInstance(mock(Indexer.class));
                bind(TaskManager.class).toInstance(taskManager);
                bind(Filter.class).to(LocalUserFilter.class).asEagerSingleton();
                bind(PropertiesProvider.class).toInstance(new PropertiesProvider(new HashMap<String, String>() {{
                    put("dataDir", "/default/data/dir");
                }}));
            }
            @Override protected Routes addModeConfiguration(Routes routes) { return routes.add(TaskResource.class);}
        }.createWebConfiguration());
    }

    @Before
    public void setUp() { init(taskFactory);}

    @After
    public void tearDown() {
        taskManager.waitTasksToBeDone(1, SECONDS);
        taskManager.cleanDoneTasks();
    }

    @Test
    public void test_index_file() {
        RestAssert response = post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), "{}");

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(Object::toString).collect(toList());
        responseBody.should().contain(format("{\"name\":\"%s\"", taskNames.get(0)));
        responseBody.should().contain(format("{\"name\":\"%s\"", taskNames.get(1)));
    }

    @Test
    public void test_index_directory() {
        RestAssert response = post("/api/task/batchUpdate/index/file/" + getClass().getResource("/docs/").getPath().substring(1), "{}");

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(Object::toString).collect(toList());
        responseBody.should().contain(format("{\"name\":\"%s\"", taskNames.get(0)));
        responseBody.should().contain(format("{\"name\":\"%s\"", taskNames.get(1)));
    }

    @Test
    public void test_index_and_scan_default_directory() {
        RestAssert response = post("/api/task/batchUpdate/index/file", "{}");

        response.should().respond(200).haveType("application/json");
        verify(taskFactory).createScanTask(local(), Paths.get("/default/data/dir"), Options.from(new HashMap<String, String>() {{
            put("dataDir", "/default/data/dir");
        }}));
    }

    @Test
    public void test_run_batch_search() {
        RestAssert response = post("/api/task/batchSearch", "{}");

        response.should().respond(200).haveType("application/json");
        verify(taskFactory).createBatchSearchRunner(local());
    }

    @Test
    public void test_index_and_scan_directory_with_options() {
        String path = getClass().getResource("/docs/").getPath();

        RestAssert response = post("/api/task/batchUpdate/index/" + path.substring(1),
                "{\"options\":{\"key1\":\"val1\",\"key2\":\"val2\"}}");

        response.should().haveType("application/json");
        verify(taskFactory).createIndexTask(local(), Options.from(new HashMap<String, String>() {{
            put("dataDir", "/default/data/dir");
            put("key1", "val1");
            put("key2", "val2");
        }}));
        verify(taskFactory).createScanTask(local(), Paths.get(path), Options.from(new HashMap<String, String>() {{
            put("dataDir", "/default/data/dir");
            put("key1", "val1");
            put("key2", "val2");
        }}));
    }

    @Test
    public void test_index_queue_with_options() {
        RestAssert response = post("/api/task/batchUpdate/index", "{\"options\":{\"key1\":\"val1\",\"key2\":\"val2\"}}");

        response.should().haveType("application/json");
        verify(taskFactory).createIndexTask(local(),  Options.from(new HashMap<String, String>() {{
            put("key1", "val1");
            put("key2", "val2");
        }}));
        verify(taskFactory, never()).createScanTask(eq(local()), any(Path.class), any(Options.class));
    }

    @Test
    public void test_scan_with_options() {
        String path = getClass().getResource("/docs/").getPath();
        RestAssert response = post("/api/task/scan/file/" + path.substring(1),
                "{\"options\":{\"key1\":\"val1\",\"key2\":\"val2\"}}");

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(Object::toString).collect(toList());
        assertThat(taskNames.size()).isEqualTo(1);
        responseBody.should().contain(format("{\"name\":\"%s\"", taskNames.get(0)));
        verify(taskFactory).createScanTask(local(), Paths.get(path), Options.from(new HashMap<String, String>() {{
            put("key1", "val1");
            put("key2", "val2");
            put("dataDir", "/default/data/dir");
        }}));
        verify(taskFactory, never()).createIndexTask(any(User.class), any(Options.class));
    }

    @Test
    public void test_findNames_should_create_resume() {
        RestAssert response = post("/api/task/findNames/OPENNLP", "{\"options\":{\"waitForNlpApp\": false}}");

        response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(Object::toString).collect(toList());
        assertThat(taskNames.size()).isEqualTo(2);
        verify(taskFactory).createResumeNlpTask(local(), singleton(Pipeline.Type.OPENNLP));

        ArgumentCaptor<AbstractPipeline> pipelineArgumentCaptor = ArgumentCaptor.forClass(AbstractPipeline.class);

        Properties properties = new Properties();
        properties.put("dataDir", "/default/data/dir");
        properties.put("waitForNlpApp", "false");
        verify(taskFactory).createNlpTask(eq(local()), pipelineArgumentCaptor.capture(), eq(properties), any());
        assertThat(pipelineArgumentCaptor.getValue().getType()).isEqualTo(Pipeline.Type.OPENNLP);
    }

    @Test
    public void test_findNames_with_options_should_merge_with_property_provider() {
        RestAssert response = post("/api/task/findNames/OPENNLP", "{\"options\":{\"waitForNlpApp\": false, \"key1\":\"val1\",\"key2\":\"val2\"}}");
        response.should().haveType("application/json");

        verify(taskFactory).createResumeNlpTask(local(), singleton(Pipeline.Type.OPENNLP));

        ArgumentCaptor<AbstractPipeline> pipelineCaptor = ArgumentCaptor.forClass(AbstractPipeline.class);
        ArgumentCaptor<Properties> propertiesCaptor = ArgumentCaptor.forClass(Properties.class);
        verify(taskFactory).createNlpTask(eq(local()), pipelineCaptor.capture(), propertiesCaptor.capture(), any());
        assertThat(propertiesCaptor.getValue()).includes(entry("key1", "val1"), entry("key2", "val2"));

        assertThat(pipelineCaptor.getValue().getType()).isEqualTo(Pipeline.Type.OPENNLP);
    }

    @Test
    public void test_findNames_with_resume_false_should_not_launch_resume_task() {
        RestAssert response = post("/api/task/findNames/OPENNLP", "{\"options\":{\"resume\":\"false\", \"waitForNlpApp\": false}}");
        response.should().haveType("application/json");

        verify(taskFactory, never()).createResumeNlpTask(null, singleton(Pipeline.Type.OPENNLP));
    }

    @Test
    public void test_findNames_with_sync_models_false() {
        AbstractModels.syncModels(true);
        RestAssert response = post("/api/task/findNames/OPENNLP", "{\"options\":{\"syncModels\":\"false\", \"waitForNlpApp\": false}}");
        response.should().haveType("application/json");

        assertThat(AbstractModels.isSync()).isFalse();
    }

    @Test
    public void test_clean_tasks() {
        post("/api/task/batchUpdate/index/file/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), "{}").response();
        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(Object::toString).collect(toList());

        ShouldChain responseBody = post("/api/task/clean", "{}").should().haveType("application/json");

        responseBody.should().contain(taskNames.get(0));
        responseBody.should().contain(taskNames.get(1));
        assertThat(taskManager.getTasks()).isEmpty();
    }

    @Test
    public void test_stop_task() {
        TaskManager.MonitorableFutureTask<String> dummyTask = taskManager.startTask(() -> {
            Thread.sleep(10000);
            return "ok";
        });
        System.out.println("/api/task/stop/" + dummyTask);
        put("/api/task/stop/" + dummyTask).should().respond(200).contain("true");

        assertThat(taskManager.getTask(dummyTask.toString()).isCancelled()).isTrue();
        get("/api/task/all").should().respond(200).contain("\"state\":\"CANCELLED\"");
    }

    @Test
    public void test_stop_unknown_task() {
        put("/api/task/stop/foobar").should().respond(404);
    }

    @Test
    public void test_stop_all() {
        TaskManager.MonitorableFutureTask<String> t1 = taskManager.startTask(() -> {
            Thread.sleep(10000);
            return "ok";
        });
        TaskManager.MonitorableFutureTask<String> t2 = taskManager.startTask(() -> {
            Thread.sleep(10000);
            return "ok";
        });
        put("/api/task/stopAll").should().respond(200).
                contain(t1.toString() + "\":true").
                contain(t2.toString() + "\":true");

        assertThat(taskManager.getTask(t1.toString()).isCancelled()).isTrue();
        assertThat(taskManager.getTask(t2.toString()).isCancelled()).isTrue();
    }

    @Test
    public void test_stop_all_filters_running_tasks() throws Exception {
        taskManager.startTask(() -> "ok");
        taskManager.waitTasksToBeDone(1, SECONDS);

        put("/api/task/stopAll").should().respond(200).contain("{}");
    }

    private void init(TaskFactory taskFactory) {
        reset(taskFactory);
        when(taskFactory.createIndexTask(any(), any())).thenReturn(mock(IndexTask.class));
        when(taskFactory.createBatchSearchRunner(any())).thenReturn(mock(BatchSearchRunner.class));
        when(taskFactory.createScanTask(any(), any(), any())).thenReturn(mock(ScanTask.class));
        when(taskFactory.createResumeNlpTask(any(), eq(singleton(Pipeline.Type.OPENNLP)))).thenReturn(mock(ResumeNlpTask.class));
        when(taskFactory.createNlpTask(any(), any())).thenReturn(mock(NlpApp.class));
        when(taskFactory.createNlpTask(any(), any(), any(), any())).thenReturn(mock(NlpApp.class));
    }
}
