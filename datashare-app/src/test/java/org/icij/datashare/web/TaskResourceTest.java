package org.icij.datashare.web;

import net.codestory.rest.Response;
import net.codestory.rest.RestAssert;
import net.codestory.rest.ShouldChain;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.bus.amqp.TaskCreation;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.nlp.EmailPipeline;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.tasks.BatchDownloadRunner;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.tasks.DeduplicateTask;
import org.icij.datashare.tasks.EnqueueFromIndexTask;
import org.icij.datashare.tasks.ExtractNlpTask;
import org.icij.datashare.tasks.IndexTask;
import org.icij.datashare.tasks.ScanIndexTask;
import org.icij.datashare.tasks.ScanTask;
import org.icij.datashare.tasks.TaskManagerMemory;
import org.icij.datashare.tasks.TestSleepingTask;
import org.icij.datashare.tasks.TestTask;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.text.nlp.AbstractModels;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.cli.DatashareCliOptions.DATA_DIR_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.REPORT_NAME_OPT;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class TaskResourceTest extends AbstractProdWebServerTest {
    @Rule
    public DatashareTimeRule time = new DatashareTimeRule("2021-07-07T12:23:34Z");
    @Mock
    JooqRepository jooqRepository;
    private static final DatashareTaskFactoryForTest taskFactory = mock(DatashareTaskFactoryForTest.class);
    private static final TaskManagerMemory taskManager = new TaskManagerMemory(taskFactory, new PropertiesProvider());

    @Before
    public void setUp() {
        initMocks(this);
        when(jooqRepository.getProjects()).thenReturn(new ArrayList<>());
        PipelineRegistry pipelineRegistry = new PipelineRegistry(getDefaultPropertiesProvider());
        pipelineRegistry.register(EmailPipeline.class);
        LocalUserFilter localUserFilter = new LocalUserFilter(getDefaultPropertiesProvider(), jooqRepository);
        configure(routes -> routes.add(new TaskResource(taskFactory, taskManager, getDefaultPropertiesProvider())).filter(localUserFilter));
        init(taskFactory);
    }

    @After
    public void tearDown() {
        taskManager.clear();
    }

    @Test
    public void test_index_file() {
        RestAssert response = post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), "{}");

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());
        responseBody.should().contain(format(taskNames.get(0)));
        responseBody.should().contain(taskNames.get(1));

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask").get().args).excludes(entry("reportName", "extract:report:map"));

    }

    @Test
    public void test_index_file_without_filter_should_not_pass_report_map_to_task() {
        String body = "{\"options\":{\"reportName\": \"foo\"}}";
        post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), body).should().haveType("application/json");

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask").get().args).excludes(entry("reportName", "foo"));
    }

    @Test
    public void test_index_file_and_filter() {
        String body = "{\"options\":{\"filter\": true}}";
        RestAssert response = post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), body);

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).toList();
        responseBody.should().contain(taskNames.get(0));
        responseBody.should().contain(taskNames.get(1));

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask").get().args).includes(entry("reportName", "extract:report:local-datashare"));
    }

    @Test
    public void test_index_file_and_filter_with_custom_report_map() {
        String body = "{\"options\":{\"filter\": true, \"defaultProject\": \"foo\"}}";
        RestAssert response = post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), body);

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).toList();
        responseBody.should().contain(taskNames.get(0));
        responseBody.should().contain(taskNames.get(1));

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask").get().args).includes(entry("reportName", "extract:report:foo"));
    }

    @Test
    public void test_index_file_and_filter_with_custom_queue() {
        String body = "{\"options\":{\"filter\": true, \"defaultProject\": \"foo\"}}";
        RestAssert response = post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), body);

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());
        responseBody.should().contain(taskNames.get(0));
        responseBody.should().contain(taskNames.get(1));
    }

    @Test
    public void test_index_directory() {
        RestAssert response = post("/api/task/batchUpdate/index/file/" + getClass().getResource("/docs/").getPath().substring(1), "{}");

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());
        responseBody.should().contain(taskNames.get(0));
        responseBody.should().contain(taskNames.get(1));
    }

    @Test(timeout = 2000)
    public void test_index_and_scan_default_directory() {
        RestAssert response = post("/api/task/batchUpdate/index/file", "{}");
        Map<String, Object> properties = getDefaultProperties();
        properties.put("foo", "bar");

        response.should().respond(200).haveType("application/json");
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ScanTask").get().args).
                includes(entry("dataDir", "/default/data/dir"));
    }

    @Test
    public void test_index_and_scan_directory_with_options() {
        String path = getClass().getResource("/docs").getPath();

        RestAssert response = post("/api/task/batchUpdate/index/" + path.substring(1),
                "{\"options\":{\"foo\":\"baz\",\"key\":\"val\"}}");

        response.should().haveType("application/json");
        Map<String, Object> defaultProperties = getDefaultProperties();
        defaultProperties.put("foo", "baz");
        defaultProperties.put("key", "val");
        defaultProperties.put("user", User.local());
        defaultProperties.put("group", new Group("Java"));
        defaultProperties.remove(REPORT_NAME_OPT);

        assertThat(taskManager.getTasks()).hasSize(2);
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ScanTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ScanTask").get().args.get(DATA_DIR_OPT)).isEqualTo(path);

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask").get().args).isEqualTo(defaultProperties);
    }

    @Test
    public void test_index_queue_with_options() {
        String body = "{\"options\":{\"key1\":\"val1\",\"key2\":\"val2\"}}";
        RestAssert response = post("/api/task/batchUpdate/index", body);
        response.should().haveType("application/json");

        Map<String, Object> defaultProperties = getDefaultProperties();
        defaultProperties.put("key1", "val1");
        defaultProperties.put("key2", "val2");
        defaultProperties.put("user", User.local());
        defaultProperties.put("group", new Group("Java"));

        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTasks().get(0).name).isEqualTo("org.icij.datashare.tasks.IndexTask");

        assertThat(taskManager.getTasks().get(0).args).isEqualTo(defaultProperties);
    }

    @Test
    public void test_scan_with_options() {
        String path = getClass().getResource("/docs").getPath();
        RestAssert response = post("/api/task/batchUpdate/scan/" + path.substring(1),
                "{\"options\":{\"key\":\"val\",\"foo\":\"qux\"}}");

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());
        assertThat(taskNames.size()).isEqualTo(1);
        responseBody.should().contain(taskNames.get(0));
        Map<String, Object> defaultProperties = getDefaultProperties();
        defaultProperties.put("key", "val");
        defaultProperties.put("foo", "qux");
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ScanTask").get().args).
                includes(entry("key", "val"), entry("foo", "qux"), entry(DATA_DIR_OPT, path));
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask")).isNotNull();
    }

    @Test
    public void test_scan_queue_is_created_correctly() {
        String body = "{\"options\":{\"filter\": true, \"defaultProject\": \"foo\"}}";
        String path = getClass().getResource("/docs/").getPath();
        RestAssert response = post("/api/task/batchUpdate/scan/" + path.substring(1), body);
        response.should().haveType("application/json");
        taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ScanTask").get().args).
                includes(entry("queueName", "extract:queue:foo:1725215461"));
    }

    @Test
    public void test_digest_project_name_is_created_correctly() {
        String body = "{\"options\":{\"filter\": true, \"defaultProject\": \"foo\"}}";
        String path = getClass().getResource("/docs/").getPath();
        RestAssert response = post("/api/task/batchUpdate/scan/" + path.substring(1), body);
        response.should().haveType("application/json");
        taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ScanTask").get().args).
                includes(entry("digestProjectName", "foo"));
    }

    @Test
    public void test_scan_queue_is_created_correctly_and_options_ignored() {
        String body = "{\"options\":{\"filter\": true, \"defaultProject\": \"foo\", \"queueName\": \"bar\"}}";
        String path = getClass().getResource("/docs/").getPath();
        RestAssert response = post("/api/task/batchUpdate/scan/" + path.substring(1), body);
        response.should().haveType("application/json");
        taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ScanTask").get().args).
                includes(entry("queueName", "extract:queue:foo:1725215461"));
    }

    @Test
    public void test_findNames_should_create_resume() {
        RestAssert response = post("/api/task/findNames/EMAIL", "{\"options\":{\"waitForNlpApp\": false}}");

        response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());
        assertThat(taskNames.size()).isEqualTo(2);

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.EnqueueFromIndexTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ExtractNlpTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ExtractNlpTask").get().args).includes(entry("nlpPipeline", "EMAIL"));
    }

    @Test
    public void test_findNames_with_options_should_merge_with_property_provider() {
        RestAssert response = post("/api/task/findNames/EMAIL", "{\"options\":{\"waitForNlpApp\": false, \"key\":\"val\",\"foo\":\"loo\"}}");
        response.should().haveType("application/json");

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.EnqueueFromIndexTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ExtractNlpTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ExtractNlpTask").get().args).
                includes(
                        entry("nlpPipeline", "EMAIL"),
                        entry("key", "val"),
                        entry("foo", "loo"));
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
        TaskResource.TaskResponse taskResponse = MAPPER.readValue(response.content(), TaskResource.TaskResponse.class);
        assertThat(taskManager.getTask(taskResponse.taskId())).isNotNull();
    }

    @Test
    public void test_batch_download_multiple_projects() throws Exception {
        Response response = post("/api/task/batchDownload", "{\"options\":{ \"projectIds\":[\"project1\", \"project2\"], \"query\": \"*\" }}").response();

        assertThat(response.contentType()).startsWith("application/json");
        TaskResource.TaskResponse taskResponse = MAPPER.readValue(response.content(), TaskResource.TaskResponse.class);
        assertThat(taskManager.getTask(taskResponse.taskId())).isNotNull();
    }

    @Test
    public void test_batch_download_uri() throws Exception {
        Response response = post("/api/task/batchDownload", "{\"options\":{ \"projectIds\":[\"test-datashare\"], \"query\": \"*\", \"uri\": \"/an%20url-encoded%20uri\" }}").response();

        assertThat(response.contentType()).startsWith("application/json");
        TaskResource.TaskResponse taskResponse = MAPPER.readValue(response.content(), TaskResource.TaskResponse.class);
        assertThat(taskManager.getTask(taskResponse.taskId())).isNotNull();
    }

    @Test
    public void test_batch_download_json_query()  throws Exception {
        Response response = post("/api/task/batchDownload", "{\"options\":{ \"projectIds\":[\"test-datashare\"], \"query\": {\"match_all\":{}} }}").response();

        assertThat(response.contentType()).startsWith("application/json");
        TaskResource.TaskResponse taskResponse = MAPPER.readValue(response.content(), TaskResource.TaskResponse.class);
        assertThat(taskManager.getTask(taskResponse.taskId())).isNotNull();
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
    public void test_clean_one_done_task() throws IOException {
        String dummyTaskId = taskManager.startTask(TestTask.class, User.local(), new HashMap<>());
        taskManager.waitTasksToBeDone(1, SECONDS);
        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTask(dummyTaskId).getState()).isEqualTo(Task.State.DONE);

        delete("/api/task/clean/" + dummyTaskId).should().respond(200);

        assertThat(taskManager.getTasks()).hasSize(0);
    }
    @Test
    public void test_cannot_clean_unknown_task() {
        delete("/api/task/clean/UNKNOWN_TASK_NAME").should().respond(404);
    }

    @Test
    public void test_clean_task_preflight() throws IOException {
        String dummyTaskId = taskManager.startTask(TestTask.class, User.local(), new HashMap<>());
        taskManager.waitTasksToBeDone(1, SECONDS);
        options("/api/task/clean/" + dummyTaskId).should().respond(200);
    }

    @Test
    public void test_cannot_clean_running_task() throws IOException {
        String dummyTaskId = taskManager.startTask(TestSleepingTask.class, User.local(), new HashMap<>());
        assertThat(taskManager.getTask(dummyTaskId).getState()).isNotEqualTo(Task.State.DONE);
        delete("/api/task/clean/" + dummyTaskId).should().respond(403);
        assertThat(taskManager.getTasks()).hasSize(1);
        // Cancel the all tasks to avoid side-effects with other tests
        put("/api/task/stopAll").should().respond(200);
    }

    @Test
    public void test_stop_task() throws IOException {
        String dummyTaskId = taskManager.startTask(TestSleepingTask.class, User.local(), new HashMap<>());
        put("/api/task/stop/" + dummyTaskId).should().respond(200).contain("true");

        assertThat(taskManager.getTask(dummyTaskId).getState()).isEqualTo(Task.State.CANCELLED);
        get("/api/task/all").should().respond(200).contain("\"state\":\"CANCELLED\"");
    }

    @Test
    public void test_stop_unknown_task() {
        put("/api/task/stop/foobar").should().respond(404);
    }

    @Test
    public void test_stop_all() throws IOException {
        String t1Id = taskManager.startTask(TestSleepingTask.class, User.local(), new HashMap<>());
        String t2Id = taskManager.startTask(TestSleepingTask.class, User.local(), new HashMap<>());
        put("/api/task/stopAll").should().respond(200).
                contain(t1Id + "\":true").
                contain(t2Id + "\":true");

        assertThat(taskManager.getTask(t1Id).getState()).isEqualTo(Task.State.CANCELLED);
        assertThat(taskManager.getTask(t2Id).getState()).isEqualTo(Task.State.CANCELLED);
    }

    @Test
    public void test_stop_all_filters_running_tasks() throws IOException {
        taskManager.startTask(TestTask.class, User.local(), new HashMap<>());
        taskManager.waitTasksToBeDone(1, SECONDS);

        put("/api/task/stopAll").should().respond(200).contain("{}");
    }

    @Test
    public void test_clear_done_tasks() throws IOException {
        taskManager.startTask(TestTask.class, User.local(), new HashMap<>());
        taskManager.waitTasksToBeDone(1, SECONDS);

        put("/api/task/stopAll").should().respond(200).contain("{}");

        assertThat(taskManager.clearDoneTasks()).hasSize(1);
        assertThat(taskManager.getTasks()).hasSize(0);
    }

    @Test
    public void test_create_new_task_not_same_id_for_url_and_json() {
        put("/api/task/my_url_task_id", """
            {"@type":"Task","id":"my_json_task_id","name":"name",
            "arguments": {"user":{"@type":"org.icij.datashare.user.User", "id":"local","name":null,"email":null,"provider":"local","details":{"uid":"local","groups_by_applications":{"datashare":["local-datashare"]}}
            }}}""")
                .should().respond(400)
                .should().haveType("application/json")
                .should().contain("{\"message\":");
    }

    @Test
    public void test_create_new_task_with_empty_json() {
        put("/api/task/my_url_task_id", """
                {"@type":"Task"}""")
                .should().respond(400)
                .should().haveType("application/json")
                .should().contain("{\"message\":");
    }

    @Test
    public void test_create_new_task() {
        put("/api/task/my_json_task_id", String.format("""
            {"@type":"Task","id":"my_json_task_id","name":"%s",
            "arguments": {"user":{"@type":"org.icij.datashare.user.User", "id":"local","name":null,"email":null,"provider":"local","details":{"uid":"local","groups_by_applications":{"datashare":["local-datashare"]}}
            }}}""", TaskCreation.class.getName()))
                .should().respond(201);
    }

    @NotNull
    private Map<String, Object> getDefaultProperties() {
        HashMap<String, Object> map = new HashMap<>() {{
            put("dataDir", "/default/data/dir");
            put("foo", "bar");
            put("batchDownloadDir", "app/tmp");
            put("defaultProject", "local-datashare");
            put("queueName", "extract:queue");
            put("reportName", "extract:report:local-datashare");
            put("digestProjectName", "local-datashare");
        }};
        // Override the queueName with
        map.put("queueName", new PropertiesProvider(PropertiesProvider.fromMap(map)).queueNameWithHash());
        return map;
    }

    @NotNull
    private PropertiesProvider getDefaultPropertiesProvider() {
        return new PropertiesProvider(getDefaultProperties());
    }

    private Optional<Task<?>> findTask(TaskManagerMemory taskManager, String expectedName) {
        return taskManager.getTasks().stream().filter(t -> expectedName.equals(t.name)).findFirst();
    }

    private void init(DatashareTaskFactoryForTest taskFactory) {
        reset(taskFactory);
        when(taskFactory.createIndexTask(any(), any())).thenReturn(mock(IndexTask.class));
        when(taskFactory.createScanTask(any(), any())).thenReturn(mock(ScanTask.class));
        when(taskFactory.createDeduplicateTask(any(), any())).thenReturn(mock(DeduplicateTask.class));
        when(taskFactory.createBatchDownloadRunner(any(), any())).thenReturn(mock(BatchDownloadRunner.class));
        when(taskFactory.createScanIndexTask(any(), any())).thenReturn(mock(ScanIndexTask.class));
        when(taskFactory.createEnqueueFromIndexTask(any(), any())).thenReturn(mock(EnqueueFromIndexTask.class));
        when(taskFactory.createExtractNlpTask(any(), any())).thenReturn(mock(ExtractNlpTask.class));
        when(taskFactory.createTestTask(any(Task.class), any(Function.class))).thenReturn(new TestTask(10));
        when(taskFactory.createTestSleepingTask(any(Task.class), any(Function.class))).thenReturn(new TestSleepingTask(100000));
        when(taskFactory.createTaskCreation(any(Task.class), any(Function.class))).thenReturn(mock(TaskCreation.class));
    }

    public interface DatashareTaskFactoryForTest extends DatashareTaskFactory {
        TestSleepingTask createTestSleepingTask(Task<Integer> task, Function<Double, Void> updateCallback);
        TestTask createTestTask(Task<Integer> task, Function<Double, Void> updateCallback);
        TaskCreation createTaskCreation(Task<?> task, Function<Double, Void> updateCallback);
    }
}
