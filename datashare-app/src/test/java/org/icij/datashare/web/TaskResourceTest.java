package org.icij.datashare.web;

import net.codestory.http.routes.Routes;
import net.codestory.rest.Response;
import net.codestory.rest.RestAssert;
import net.codestory.rest.ShouldChain;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.nlp.EmailPipeline;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.tasks.BatchDownloadRunner;
import org.icij.datashare.tasks.DeduplicateTask;
import org.icij.datashare.tasks.EnqueueFromIndexTask;
import org.icij.datashare.tasks.ExtractNlpTask;
import org.icij.datashare.tasks.IndexTask;
import org.icij.datashare.tasks.ScanIndexTask;
import org.icij.datashare.tasks.ScanTask;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.tasks.TaskManager;
import org.icij.datashare.tasks.TaskManagerMemory;
import org.icij.datashare.tasks.TaskModifier;
import org.icij.datashare.tasks.TaskSupplier;
import org.icij.datashare.tasks.TaskView;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.AbstractModels;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;
import static org.icij.datashare.session.DatashareUser.local;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class TaskResourceTest extends AbstractProdWebServerTest {
    @Rule public DatashareTimeRule time = new DatashareTimeRule("2021-07-07T12:23:34Z");
    @Mock JooqRepository jooqRepository;
    private static final TaskFactory taskFactory = mock(TaskFactory.class);
    private static final BlockingQueue<TaskView<?>> taskQueue = new ArrayBlockingQueue<>(3);
    private static final TaskManagerMemory taskManager= new TaskManagerMemory(new PropertiesProvider(), taskQueue);

    @Before
    public void setUp() {
        initMocks(this);
        when(jooqRepository.getProjects()).thenReturn(new ArrayList<>());
        final PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
            put("mode", "LOCAL");
        }});
        PipelineRegistry pipelineRegistry = new PipelineRegistry(propertiesProvider);
        pipelineRegistry.register(EmailPipeline.class);
        LocalUserFilter localUserFilter = new LocalUserFilter(propertiesProvider, jooqRepository);
        configure(new CommonMode(propertiesProvider.getProperties()) {
                    @Override
                    protected void configure() {
                        bind(TaskFactory.class).toInstance(taskFactory);
                        bind(Indexer.class).toInstance(mock(Indexer.class));
                        bind(TaskManager.class).toInstance(taskManager);
                        bind(TaskSupplier.class).toInstance(taskManager);
                        bind(TaskModifier.class).toInstance(taskManager);
                        bind(PipelineRegistry.class).toInstance(pipelineRegistry);
                        bind(LocalUserFilter.class).toInstance(localUserFilter);
                        bind(PropertiesProvider.class).toInstance(new PropertiesProvider(getDefaultProperties()));
                    }
            @Override protected Routes addModeConfiguration(Routes routes) {
                        return routes.add(TaskResource.class).filter(LocalUserFilter.class);}
                }.createWebConfiguration());
        init(taskFactory);
    }

    @After
    public void tearDown() {
        taskManager.waitTasksToBeDone(1, SECONDS);
        taskManager.clearDoneTasks();
    }

    @Test
    public void test_index_file() {
        RestAssert response = post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), "{}");

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(0)));
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(1)));
    }

    @Test
    public void test_index_file_and_filter() {
        String body ="{\"options\":{\"filter\": true}}";
        RestAssert response = post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), body);

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(0)));
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(1)));

        verify(taskFactory).createScanIndexTask(eq(local()), eq("extract:report"));
    }

    @Test
    public void test_index_file_and_filter_with_custom_report_map() {
        String body = "{\"options\":{\"filter\": true, \"reportName\": \"extract:report:foo\"}}";
        RestAssert response = post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), body);

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(0)));
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(1)));

        verify(taskFactory).createScanIndexTask(eq(local()), eq("extract:report:foo"));
    }

    @Test
    public void test_index_file_and_filter_with_custom_queue() {
        String body = "{\"options\":{\"filter\": true, \"queueName\": \"extract:queue:foo\"}}";
        RestAssert response = post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), body);

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(0)));
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(1)));
    }

    @Test
    public void test_index_directory() {
        RestAssert response = post("/api/task/batchUpdate/index/file/" + getClass().getResource("/docs/").getPath().substring(1), "{}");

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(0)));
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(1)));
    }

    @Test
    public void test_index_and_scan_default_directory() {
        RestAssert response = post("/api/task/batchUpdate/index/file", "{}");
        HashMap<String, Object> properties = getDefaultProperties();
        properties.put("foo", "bar");

        response.should().respond(200).haveType("application/json");
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ScanTask").get().properties).
                includes(entry("dataDir", "/default/data/dir"));
    }

    @Test
    public void test_index_and_scan_directory_with_options() {
        String path = getClass().getResource("/docs/").getPath();

        RestAssert response = post("/api/task/batchUpdate/index/" + path.substring(1),
                "{\"options\":{\"foo\":\"baz\",\"key\":\"val\"}}");

        response.should().haveType("application/json");
        HashMap<String, Object> defaultProperties = getDefaultProperties();
        defaultProperties.put("foo", "baz");
        defaultProperties.put("key", "val");
        assertThat(taskManager.getTasks()).hasSize(2);
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask").get().properties).isEqualTo(defaultProperties);
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ScanTask")).isNotNull();
    }

    @Test
    public void test_index_queue_with_options() {
        RestAssert response = post("/api/task/batchUpdate/index", "{\"options\":{\"key1\":\"val1\",\"key2\":\"val2\"}}");

        response.should().haveType("application/json");
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask").get().properties).isEqualTo((new HashMap<>() {{
            put("key1", "val1");
            put("key2", "val2");
        }}));
        assertThat(taskManager.getTasks()).hasSize(1);
    }

    @Test
    public void test_scan_with_options() {
        String path = getClass().getResource("/docs/").getPath();
        RestAssert response = post("/api/task/batchUpdate/scan/" + path.substring(1),
                "{\"options\":{\"key\":\"val\",\"foo\":\"qux\"}}");

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());
        assertThat(taskNames.size()).isEqualTo(1);
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(0)));
        HashMap<String, Object> defaultProperties = getDefaultProperties();
        defaultProperties.put("key", "val");
        defaultProperties.put("foo", "qux");
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ScanTask").get().properties).
                includes(entry("key", "val"), entry("foo", "qux"));
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask")).isNotNull();
    }

    @Test
    public void test_findNames_should_create_resume() {
        RestAssert response = post("/api/task/findNames/EMAIL", "{\"options\":{\"waitForNlpApp\": false}}");

        response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());
        assertThat(taskNames.size()).isEqualTo(2);
        ArgumentCaptor<Properties> propertiesArgumentCaptor = ArgumentCaptor.forClass(Properties.class);

        verify(taskFactory).createEnqueueFromIndexTask(eq(local()), propertiesArgumentCaptor.capture());
        assertThat(propertiesArgumentCaptor.getValue()).includes(entry("nlpPipeline", "EMAIL"));

        HashMap<String, Object> properties = getDefaultProperties();
        properties.put("waitForNlpApp", "false");
        verify(taskFactory).createNlpTask(eq(local()), propertiesArgumentCaptor.capture());
        assertThat(propertiesArgumentCaptor.getValue()).includes(entry("nlpPipeline", "EMAIL"));
    }

    @Test
    public void test_findNames_with_options_should_merge_with_property_provider() {
        RestAssert response = post("/api/task/findNames/EMAIL", "{\"options\":{\"waitForNlpApp\": false, \"key\":\"val\",\"foo\":\"loo\"}}");
        response.should().haveType("application/json");
        ArgumentCaptor<Properties> propertiesArgumentCaptor = ArgumentCaptor.forClass(Properties.class);
        verify(taskFactory).createEnqueueFromIndexTask(eq(local()), propertiesArgumentCaptor.capture());
        assertThat(propertiesArgumentCaptor.getValue()).includes(entry("nlpPipeline", "EMAIL"));

        verify(taskFactory).createNlpTask(eq(local()), propertiesArgumentCaptor.capture());
        assertThat(propertiesArgumentCaptor.getValue()).includes(entry("key", "val"), entry("foo", "loo"));
        assertThat(propertiesArgumentCaptor.getValue()).includes(entry("nlpPipeline", "EMAIL"));
    }

    @Test
    public void test_findNames_with_resume_false_should_not_launch_resume_task() {
        RestAssert response = post("/api/task/findNames/EMAIL", "{\"options\":{\"resume\":\"false\", \"waitForNlpApp\": false}}");
        response.should().haveType("application/json");

        verify(taskFactory, never()).createEnqueueFromIndexTask(eq(null), any());
    }

    @Test
    public void test_findNames_with_sync_models_false() {
        AbstractModels.syncModels(true);
        RestAssert response = post("/api/task/findNames/EMAIL", "{\"options\":{\"syncModels\":\"false\", \"waitForNlpApp\": false}}");
        response.should().haveType("application/json");

        assertThat(AbstractModels.isSync()).isFalse();
    }

    @Test
    public void test_batch_download() throws Exception {
        Response response = post("/api/task/batchDownload", "{\"options\":{ \"projectIds\":[\"test-datashare\"], \"query\": \"*\" }}").response();

        assertThat(response.contentType()).startsWith("application/json");
        TaskView<?> task = MAPPER.readValue(response.content(), TaskView.class);
        assertThat(taskQueue.contains(task));
    }

    @Test
    public void test_batch_download_multiple_projects() throws Exception {
        Response response = post("/api/task/batchDownload", "{\"options\":{ \"projectIds\":[\"project1\", \"project2\"], \"query\": \"*\" }}").response();

        assertThat(response.contentType()).startsWith("application/json");
        TaskView<?> task = MAPPER.readValue(response.content(), TaskView.class);
        assertThat(taskQueue.contains(task));
    }

    @Test
    public void test_batch_download_uri() throws Exception  {
        Response response = post("/api/task/batchDownload", "{\"options\":{ \"projectIds\":[\"test-datashare\"], \"query\": \"*\", \"uri\": \"/an%20url-encoded%20uri\" }}").response();

        assertThat(response.contentType()).startsWith("application/json");
        TaskView<?> task = MAPPER.readValue(response.content(), TaskView.class);
        assertThat(taskQueue.contains(task));
    }


    @Test
    public void test_batch_download_json_query()  throws Exception {
        Response response = post("/api/task/batchDownload", "{\"options\":{ \"projectIds\":[\"test-datashare\"], \"query\": {\"match_all\":{}} }}").response();

        assertThat(response.contentType()).startsWith("application/json");
        TaskView<?> task = MAPPER.readValue(response.content(), TaskView.class);
        assertThat(taskQueue.contains(task));
    }

    @Test
    public void test_clean_tasks() {
        post("/api/task/batchUpdate/index/file/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), "{}").response();
        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());

        ShouldChain responseBody = post("/api/task/clean", "{}").should().haveType("application/json");

        responseBody.should().contain(taskNames.get(0));
        responseBody.should().contain(taskNames.get(1));
        assertThat(taskManager.getTasks()).isEmpty();
    }

    @Test
    public void test_clean_one_done_task() {
        TaskView<String> dummyTask = taskManager.startTask(() ->  "ok");
        taskManager.waitTasksToBeDone(1, SECONDS);
        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTask(dummyTask.id).getState()).isEqualTo(TaskView.State.DONE);
        delete("/api/task/clean/" + dummyTask.id).should().respond(200);
        assertThat(taskManager.getTasks()).hasSize(0);
    }
    @Test
    public void test_cannot_clean_unknown_task() {
        delete("/api/task/clean/UNKNOWN_TASK_NAME").should().respond(404);
    }

    @Test
    public void test_clean_task_preflight() {
        TaskView<String> dummyTask = taskManager.startTask(() ->  "ok");
        taskManager.waitTasksToBeDone(1, SECONDS);
        options("/api/task/clean/" + dummyTask.id).should().respond(200);
    }

    @Test
    public void test_cannot_clean_running_task() {
        TaskView<String> dummyTask = taskManager.startTask(() -> {
            Thread.sleep(10000);
            return "ok";
        });
        assertThat(taskManager.getTask(dummyTask.id).getState()).isEqualTo(TaskView.State.RUNNING);
        delete("/api/task/clean/" + dummyTask.id).should().respond(403);
        assertThat(taskManager.getTasks()).hasSize(1);
        // Cancel the all tasks to avoid side-effects with other tests
        put("/api/task/stopAll").should().respond(200);
    }

    @Test
    public void test_stop_task() {
        TaskView<String> dummyTask = taskManager.startTask(() -> {
            Thread.sleep(10000);
            return "ok";
        });
        put("/api/task/stop/" + dummyTask.id).should().respond(200).contain("true");

        assertThat(taskManager.getTask(dummyTask.id).getState()).isEqualTo(TaskView.State.CANCELLED);
        get("/api/task/all").should().respond(200).contain("\"state\":\"CANCELLED\"");
    }

    @Test
    public void test_stop_unknown_task() {
        put("/api/task/stop/foobar").should().respond(404);
    }

    @Test
    public void test_stop_all() {
        TaskView<String> t1 = taskManager.startTask(() -> {
            Thread.sleep(10000);
            return "ok";
        });
        TaskView<String> t2 = taskManager.startTask(() -> {
            Thread.sleep(10000);
            return "ok";
        });
        put("/api/task/stopAll").should().respond(200).
                contain(t1.id + "\":true").
                contain(t2.id + "\":true");

        assertThat(taskManager.getTask(t1.id).getState()).isEqualTo(TaskView.State.CANCELLED);
        assertThat(taskManager.getTask(t2.id).getState()).isEqualTo(TaskView.State.CANCELLED);
    }

    @Test
    public void test_stop_all_filters_running_tasks() {
        taskManager.startTask(() -> "ok");
        taskManager.waitTasksToBeDone(1, SECONDS);

        put("/api/task/stopAll").should().respond(200).contain("{}");
    }

    @Test
    public void test_clear_done_tasks() {
        taskManager.startTask(() -> "ok");
        taskManager.waitTasksToBeDone(1, SECONDS);

        put("/api/task/stopAll").should().respond(200).contain("{}");

        assertThat(taskManager.clearDoneTasks()).hasSize(1);
        assertThat(taskManager.getTasks()).hasSize(0);
    }

    @NotNull
    private HashMap<String, Object> getDefaultProperties() {
        return new HashMap<>() {{
            put("dataDir", "/default/data/dir");
            put("foo", "bar");
            put("batchDownloadDir", "app/tmp");
        }};
    }

    private TaskView<?> taskView(BatchDownload batchDownload) {
        return new TaskView<File>(batchDownload.uuid, batchDownload.user, new HashMap<>() {{
            put("batchDownload", batchDownload);
        }});
    }

    private Optional<TaskView<?>> findTask(TaskManagerMemory taskManager, String expectedName) {
        return taskManager.getTasks().stream().filter(t -> expectedName.equals(t.name)).findFirst();
    }

    private void init(TaskFactory taskFactory) {
        reset(taskFactory);
        when(taskFactory.createIndexTask(any(), any())).thenReturn(mock(IndexTask.class));
        when(taskFactory.createScanTask(any(), any())).thenReturn(mock(ScanTask.class));
        when(taskFactory.createDeduplicateTask(any())).thenReturn(mock(DeduplicateTask.class));
        when(taskFactory.createBatchDownloadRunner(any(), any())).thenReturn(mock(BatchDownloadRunner.class));
        when(taskFactory.createScanIndexTask(any(), any())).thenReturn(mock(ScanIndexTask.class));
        when(taskFactory.createEnqueueFromIndexTask(any(), any())).thenReturn(mock(EnqueueFromIndexTask.class));
        when(taskFactory.createNlpTask(any(), any())).thenReturn(mock(ExtractNlpTask.class));
    }
}
