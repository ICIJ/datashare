package org.icij.datashare.web;

import com.fasterxml.jackson.core.type.TypeReference;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.*;

import net.codestory.http.Context;
import net.codestory.http.Cookies;
import net.codestory.http.Part;
import net.codestory.http.Query;
import net.codestory.http.Request;
import net.codestory.http.errors.BadRequestException;
import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.rest.Response;
import net.codestory.rest.RestAssert;
import net.codestory.rest.ShouldChain;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskFilters;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.asynctasks.bus.amqp.TaskCreation;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.nlp.EmailPipeline;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.tasks.*;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.text.ProjectProxy;
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

import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.asynctasks.Task.State.DONE;
import static org.icij.datashare.asynctasks.Task.State.RUNNING;
import static org.icij.datashare.cli.DatashareCliOptions.*;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;
import static org.icij.datashare.session.DatashareUser.singleUser;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.local;
import static org.icij.datashare.web.TaskResource.taskFiltersFromContext;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class TaskResourceTest extends AbstractProdWebServerTest {
    @Rule
    public DatashareTimeRule time = new DatashareTimeRule("2021-07-07T12:23:34Z");
    @Mock
    JooqRepository jooqRepository;
    @Mock
    BatchSearchRepository batchSearchRepository;

    private static final TestTaskUtils.DatashareTaskFactoryForTest taskFactory = mock(TestTaskUtils.DatashareTaskFactoryForTest.class);
    private static final TaskManagerMemory taskManager = new TaskManagerMemory(taskFactory, new TaskRepositoryMemory(), new PropertiesProvider(Map.of(TASK_MANAGER_POLLING_INTERVAL_OPT, "500")));

    @Before
    public void setUp() {
        initMocks(this);
        when(jooqRepository.getProjects()).thenReturn(new ArrayList<>());
        when(batchSearchRepository.getRecords(any(), any())).thenReturn(new ArrayList<>());
        PipelineRegistry pipelineRegistry = new PipelineRegistry(getDefaultPropertiesProvider());
        pipelineRegistry.register(EmailPipeline.class);
        LocalUserFilter localUserFilter = new LocalUserFilter(getDefaultPropertiesProvider(), jooqRepository);
        configure(routes -> routes.add(new TaskResource(taskFactory, taskManager, getDefaultPropertiesProvider(), batchSearchRepository, MAPPER)).filter(localUserFilter));
        TestTaskUtils.init(taskFactory);
    }

    @After
    public void tearDown() throws IOException {
        taskManager.stopTasks(User.local());
        taskManager.clear();
    }

    @Test
    public void test_get_tasks_with_correct_id() throws IOException {
        String dummyTaskId = taskManager.startTask(TestSleepingTask.class, User.local(), new HashMap<>());
        get("/api/task").should()
                .respond(200)
                .contain("\"id\":\"" + dummyTaskId + "\"")
                .contain("\"state\":\"RUNNING\"");
        put("/api/task/stop/" + dummyTaskId).should()
                .respond(200)
                .contain("true");
        get("/api/task").should()
                .respond(200)
                .contain("\"id\":\"" + dummyTaskId + "\"")
                .contain("\"state\":\"CANCELLED\"");
    }

    @Test
    public void test_get_tasks() {
        String subpath = getClass().getResource("/docs/doc.txt").getPath().substring(1);
        String body = "{\"options\":{\"reportName\": \"foo\"}}";
        post("/api/task/batchUpdate/index/" + subpath, body).should().haveType("application/json");

        get("/api/task?args.dataDir=docs").should().contain("ScanTask").not().contain("IndexTask");
        get("/api/task").should().haveType("application/json").contain("IndexTask").contain("ScanTask");
        get("/api/task?name=Index").should().contain("IndexTask").not().contain("ScanTask");
    }

    @Test
    public void test_get_tasks_filtered_on_multiple_names() {
        String subpath = getClass().getResource("/docs/doc.txt").getPath().substring(1);
        String body = "{\"options\":{\"reportName\": \"foo\"}}";
        post("/api/task/batchUpdate/index/" + subpath, body).should().haveType("application/json");

        get("/api/task").should().haveType("application/json").contain("IndexTask").contain("ScanTask");
        get("/api/task?name=scan|index").should().contain("ScanTask").contain("IndexTask");
    }

    @Test
    public void test_get_tasks_including_batch_search_runner_proxy() {
        List<ProjectProxy> projects = List.of(project("project"));
        BatchSearchRecord batchSearchRecord = new BatchSearchRecord(projects, "name", "description", 123, new Date(), "/");

        when(batchSearchRepository.getRecords(any(), any())).thenReturn(List.of(batchSearchRecord));

        get("/api/task").should().contain("BatchSearchRunnerProxy");
    }

    @Test
    public void test_get_tasks_including_filtered_batch_search_runner_proxy() {
        List<ProjectProxy> projects = List.of(project("project"));
        BatchSearchRecord batchSearchRecord = new BatchSearchRecord(projects, "foo", "bar", 123, new Date(), "/");

        when(batchSearchRepository.getRecords(any(), any())).thenReturn(List.of(batchSearchRecord));

        get("/api/task").should().contain("BatchSearchRunnerProxy");
        get("/api/task?args.batchRecord.name=foo").should().contain("BatchSearchRunnerProxy");
        get("/api/task?args.batchRecord.name=oo").should().contain("BatchSearchRunnerProxy");
    }

    @Test
    public void test_get_tasks_paginated_with_zero_tasks() {
        get("/api/task?size=10").should().haveType("application/json")
                .contain("\"count\":0")
                .contain("\"total\":0")
                .contain("\"from\":0")
                .contain("\"size\":10");
    }

    @Test
    public void test_get_tasks_paginated_with_two_tasks() {
        String subpath = getClass().getResource("/docs/doc.txt").getPath().substring(1);
        String body = "{\"options\":{\"reportName\": \"foo\"}}";
        post("/api/task/batchUpdate/index/" + subpath, body).should().haveType("application/json");

        get("/api/task?size=4").should().haveType("application/json")
                .contain("\"count\":2")
                .contain("\"total\":2")
                .contain("\"from\":0")
                .contain("\"size\":4");
    }

    @Test
    public void test_get_tasks_paginated_with_four_tasks_from_first_page() {
        String subpath = getClass().getResource("/docs/doc.txt").getPath().substring(1);
        String body = "{\"options\":{\"reportName\": \"foo\"}}";
        post("/api/task/batchUpdate/index/" + subpath, body).should().haveType("application/json");
        post("/api/task/batchUpdate/index/" + subpath, body).should().haveType("application/json");

        get("/api/task?size=2").should().haveType("application/json")
                .contain("\"count\":2")
                .contain("\"total\":4")
                .contain("\"from\":0")
                .contain("\"size\":2");
    }

    @Test
    public void test_get_tasks_paginated_with_four_tasks_from_second_page() {
        String subpath = getClass().getResource("/docs/doc.txt").getPath().substring(1);
        String body = "{\"options\":{\"reportName\": \"foo\"}}";
        post("/api/task/batchUpdate/index/" + subpath, body).should().haveType("application/json");
        post("/api/task/batchUpdate/index/" + subpath, body).should().haveType("application/json");

        get("/api/task?size=2&from=2").should().haveType("application/json")
                .contain("\"count\":2")
                .contain("\"total\":4")
                .contain("\"from\":2")
                .contain("\"size\":2");
    }

    @Test
    public void test_get_tasks_filter() {
        String subpath = getClass().getResource("/docs/doc.txt").getPath().substring(1);
        String bodyFoo = "{\"options\":{\"defaultProject\": \"foo\"}}";
        String bodyBar = "{\"options\":{\"defaultProject\": \"bar\"}}";
        post("/api/task/batchUpdate/index/" + subpath, bodyFoo).should().haveType("application/json");
        post("/api/task/batchUpdate/index/" + subpath, bodyBar).should().haveType("application/json");

        get("/api/task?size=1").should().contain("\"count\":1").contain("\"total\":4");
        get("/api/task?size=1&args.defaultProject=foo").should().contain("\"count\":1").contain("\"total\":2");
        get("/api/task?size=1&args.defaultProject=bar").should().contain("\"count\":1").contain("\"total\":2");
        get("/api/task?size=1&args.defaultProject=ice").should().contain("\"count\":0").contain("\"total\":0");
    }

    @Test
    public void test_get_tasks_filter_case_insensitive() {
        String subpath = getClass().getResource("/docs/doc.txt").getPath().substring(1);
        String bodyFoo = "{\"options\":{\"defaultProject\": \"foo\"}}";
        String bodyBar = "{\"options\":{\"defaultProject\": \"bar\"}}";
        post("/api/task/batchUpdate/index/" + subpath, bodyFoo).should().haveType("application/json");
        post("/api/task/batchUpdate/index/" + subpath, bodyBar).should().haveType("application/json");

        get("/api/task?size=1").should().contain("\"count\":1").contain("\"total\":4");
        get("/api/task?size=1&args.defaultProject=FOO")
                .should()
                .contain("\"count\":1")
                .contain("\"total\":2")
                .contain("\"defaultProject\":\"foo\"");
    }

    @Test
    public void test_get_all_tasks_filter() {
        post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1),
                "{\"options\":{\"reportName\": \"foo\"}}").should().haveType("application/json");

        get("/api/task/all").should().haveType("application/json").contain("IndexTask").contain("ScanTask");
        get("/api/task/all?name=Index").should().contain("IndexTask").not().contain("ScanTask");
        get("/api/task/all?args.dataDir=docs").should().contain("ScanTask").not().contain("IndexTask");
    }

    @Test
    public void test_get_all_tasks_paginated() throws Exception {
        post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1),
                "{\"options\":{\"reportName\": \"foo1\"}}").should().haveType("application/json");
        post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/embedded_doc.eml").getPath().substring(1),
                "{\"options\":{\"reportName\": \"foo2\"}}").should().haveType("application/json");

        List<Map<String, Object>> jsonTasks = MAPPER.readValue(get("/api/task/all").response().content(), new TypeReference<>() {});
        assertThat(jsonTasks).hasSize(4);

        List<Map<String, Object>> twoFirst = MAPPER.readValue(get("/api/task/all?size=2").response().content(), new TypeReference<>() {});
        assertThat(twoFirst).hasSize(2);
        List<Map<String, Object>> twoLast = MAPPER.readValue(get("/api/task/all?size=2&from=2").response().content(), new TypeReference<>() {});
        assertThat(twoLast).hasSize(2);
        assertThat(twoFirst).isNotEqualTo(twoLast);
    }

    @Test
    public void test_get_all_tasks_sorted() throws Exception {
        post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1),
                "{\"options\":{\"reportName\": \"foo\"}}").should().haveType("application/json");
        assertThat(MAPPER.readValue(get("/api/task/all").response().content(), new TypeReference<List<Map<String, Object>>>() {}).stream()
                .map(t -> t.get("name")).toList()).isEqualTo(List.of("org.icij.datashare.tasks.IndexTask", "org.icij.datashare.tasks.ScanTask"));
        assertThat(MAPPER.readValue(get("/api/task/all?order=desc").response().content(), new TypeReference<List<Map<String, Object>>>() {}).stream()
                .map(t -> t.get("name")).toList()).isEqualTo(List.of("org.icij.datashare.tasks.ScanTask", "org.icij.datashare.tasks.IndexTask"));
    }

    @Test
    public void test_index_file() throws IOException {
        RestAssert response = post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), "{}");

        ShouldChain responseBody = response.should().haveType("application/json");

        taskManager.waitTasksToBeDone(1, SECONDS);
        List<String> taskNames = taskManager.getTasks().map(t -> t.id).toList();
        responseBody.should().contain(format(taskNames.get(0)));
        responseBody.should().contain(taskNames.get(1));

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask").get().args).excludes(entry("reportName", "extract:report:map"));
    }

    @Test
    public void test_index_file_forbidden_in_server_mode() {
        configure(routes -> {
            PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of("mode", Mode.SERVER.name()));
            TaskResource taskResource = new TaskResource(taskFactory, taskManager, propertiesProvider, batchSearchRepository, MAPPER);
            BasicAuthFilter basicAuthFilter = new BasicAuthFilter("/", "icij", singleUser(local()));
            routes.filter(basicAuthFilter).add(taskResource);
        });

        String path = "/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1);
        RestAssert response = post(path, "{}").withPreemptiveAuthentication("local", "pass");
        response.should().respond(403);
    }

    @Test
    public void test_index_forbidden_in_server_mode() {
        configure(routes -> {
            PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of("mode", Mode.SERVER.name()));
            TaskResource taskResource = new TaskResource(taskFactory, taskManager, propertiesProvider, batchSearchRepository, MAPPER);
            BasicAuthFilter basicAuthFilter = new BasicAuthFilter("/", "icij", singleUser(local()));
            routes.filter(basicAuthFilter).add(taskResource);
        });

        String path = "/api/task/batchUpdate/index";
        RestAssert response = post(path, "{}").withPreemptiveAuthentication("local", "pass");
        response.should().respond(403);
    }


    @Test
    public void test_index_file_without_filter_should_not_pass_report_map_to_task() throws IOException {
        String body = "{\"options\":{\"reportName\": \"foo\"}}";
        post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), body).should().haveType("application/json");

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask").get().args).excludes(entry("reportName", "foo"));
    }

    @Test
    public void test_index_file_and_filter() throws IOException {
        String body = "{\"options\":{\"filter\": true}}";
        RestAssert response = post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), body);

        ShouldChain responseBody = response.should().haveType("application/json");

        taskManager.waitTasksToBeDone(1, SECONDS);
        List<String> taskNames = taskManager.getTasks().map(t -> t.id).toList();
        responseBody.should().contain(taskNames.get(0));
        responseBody.should().contain(taskNames.get(1));

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask").get().args).includes(entry("reportName", "extract:report:local-datashare"));
    }

    @Test
    public void test_index_file_and_filter_with_custom_report_map() throws IOException {
        String body = "{\"options\":{\"filter\": true, \"defaultProject\": \"foo\"}}";
        RestAssert response = post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), body);

        ShouldChain responseBody = response.should().haveType("application/json");

        taskManager.waitTasksToBeDone(1, SECONDS);
        List<String> taskNames = taskManager.getTasks().map(t -> t.id).toList();
        responseBody.should().contain(taskNames.get(0));
        responseBody.should().contain(taskNames.get(1));

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask").get().args).includes(entry("reportName", "extract:report:foo"));
    }

    @Test
    public void test_index_file_and_filter_with_custom_queue() throws IOException {
        String body = "{\"options\":{\"filter\": true, \"defaultProject\": \"foo\"}}";
        RestAssert response = post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), body);

        ShouldChain responseBody = response.should().haveType("application/json");

        taskManager.waitTasksToBeDone(1, SECONDS);
        List<String> taskNames = taskManager.getTasks().map(t -> t.id).toList();
        responseBody.should().contain(taskNames.get(0));
        responseBody.should().contain(taskNames.get(1));
    }

    @Test
    public void test_index_directory() throws IOException {
        RestAssert response = post("/api/task/batchUpdate/index/file/" + getClass().getResource("/docs/").getPath().substring(1), "{}");

        ShouldChain responseBody = response.should().haveType("application/json");

        taskManager.waitTasksToBeDone(1, SECONDS);
        List<String> taskNames = taskManager.getTasks().map(t -> t.id).toList();
        responseBody.should().contain(taskNames.get(0));
        responseBody.should().contain(taskNames.get(1));
    }

    @Test(timeout = 2000)
    public void test_index_and_scan_default_directory() throws IOException {
        RestAssert response = post("/api/task/batchUpdate/index/file", "{}");
        Map<String, Object> properties = getDefaultProperties();
        properties.put("foo", "bar");

        response.should().respond(200).haveType("application/json");
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ScanTask").get().args).
                includes(entry("dataDir", "/default/data/dir"));
    }

    @Test
    public void test_index_and_scan_directory_with_options() throws IOException {
        String path = Objects.requireNonNull(getClass().getResource("/docs")).getPath();

        RestAssert response = post("/api/task/batchUpdate/index/" + path.substring(1),
                "{\"options\":{\"foo\":\"baz\",\"key\":\"val\"}}");

        response.should().haveType("application/json");
        Map<String, Object> defaultProperties = getDefaultProperties();
        defaultProperties.put("foo", "baz");
        defaultProperties.put("key", "val");
        defaultProperties.put("user", User.local());
        defaultProperties.remove(REPORT_NAME_OPT);

        assertThat(taskManager.getTasks().toList()).hasSize(2);
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ScanTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ScanTask").get().args.get(DATA_DIR_OPT)).isEqualTo(path);

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask").get().args).isEqualTo(defaultProperties);
    }

    @Test
    public void test_index_queue_with_options() throws IOException {
        String body = "{\"options\":{\"key1\":\"val1\",\"key2\":\"val2\"}}";
        RestAssert response = post("/api/task/batchUpdate/index", body);
        response.should().haveType("application/json");

        Map<String, Object> defaultProperties = getDefaultProperties();
        defaultProperties.put("key1", "val1");
        defaultProperties.put("key2", "val2");
        defaultProperties.put("user", User.local());

        List<Task> tasks = taskManager.getTasks().toList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).name).isEqualTo("org.icij.datashare.tasks.IndexTask");

        assertThat(tasks.get(0).args).isEqualTo(defaultProperties);
    }

    @Test
    public void test_scan_with_options() throws IOException {
        String path = getClass().getResource("/docs").getPath();
        RestAssert response = post("/api/task/batchUpdate/scan/" + path.substring(1),
                "{\"options\":{\"key\":\"val\",\"foo\":\"qux\"}}");

        ShouldChain responseBody = response.should().haveType("application/json");

        taskManager.waitTasksToBeDone(1, SECONDS);
        List<String> taskNames = taskManager.getTasks().map(t -> t.id).toList();
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
    public void test_scan_forbidden_in_server_mode() {
        configure(routes -> {
            PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of("mode", Mode.SERVER.name()));
            TaskResource taskResource = new TaskResource(taskFactory, taskManager, propertiesProvider, batchSearchRepository, MAPPER);
            BasicAuthFilter basicAuthFilter = new BasicAuthFilter("/", "icij", singleUser(local()));
            routes.filter(basicAuthFilter).add(taskResource);
        });

        String path = "/api/task/batchUpdate/scan/" + getClass().getResource("/docs").getPath().substring(1);
        RestAssert response = post(path, "{}").withPreemptiveAuthentication("local", "pass");
        response.should().respond(403);
    }

    @Test
    public void test_scan_queue_is_created_correctly() throws IOException {
        String body = "{\"options\":{\"filter\": true, \"defaultProject\": \"foo\"}}";
        String path = Objects.requireNonNull(getClass().getResource("/docs/")).getPath();
        RestAssert response = post("/api/task/batchUpdate/scan/" + path.substring(1), body);
        response.should().haveType("application/json");
        taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).toList();

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ScanTask").get().args).
                includes(entry("queueName", "extract:queue:foo:1725215461"));
    }

    @Test
    public void test_digest_project_name_is_created_correctly() throws IOException {
        String body = "{\"options\":{\"filter\": true, \"defaultProject\": \"foo\"}}";
        String path = Objects.requireNonNull(getClass().getResource("/docs/")).getPath();
        RestAssert response = post("/api/task/batchUpdate/scan/" + path.substring(1), body);
        response.should().haveType("application/json");
        taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).toList();

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ScanTask").get().args).
                includes(entry("digestProjectName", "foo"));
    }

    @Test
    public void test_scan_queue_is_created_correctly_and_options_ignored() throws IOException {
        String body = "{\"options\":{\"filter\": true, \"defaultProject\": \"foo\", \"queueName\": \"bar\"}}";
        String path = Objects.requireNonNull(getClass().getResource("/docs/")).getPath();
        RestAssert response = post("/api/task/batchUpdate/scan/" + path.substring(1), body);
        response.should().haveType("application/json");
        taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).toList();

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ScanTask").get().args).
                includes(entry("queueName", "extract:queue:foo:1725215461"));
    }

    @Test
    public void test_findNames_should_create_resume() throws IOException {
        RestAssert response = post("/api/task/findNames/EMAIL", "{\"options\":{\"waitForNlpApp\": false}}");

        response.should().haveType("application/json");

        taskManager.waitTasksToBeDone(1, SECONDS);
        List<String> taskNames = taskManager.getTasks().map(t -> t.id).toList();
        assertThat(taskNames.size()).isEqualTo(2);

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.EnqueueFromIndexTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ExtractNlpTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ExtractNlpTask").get().args).includes(entry("nlpPipeline", "EMAIL"));
    }

    @Test
    public void test_findNames_forbidden_in_server_mode() {
        configure(routes -> {
            PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of("mode", Mode.SERVER.name()));
            TaskResource taskResource = new TaskResource(taskFactory, taskManager, propertiesProvider, batchSearchRepository, MAPPER);
            BasicAuthFilter basicAuthFilter = new BasicAuthFilter("/", "icij", singleUser(local()));
            routes.filter(basicAuthFilter).add(taskResource);
        });

        RestAssert response = post("/api/task/findNames/EMAIL", "{}")
                .withPreemptiveAuthentication("local", "pass");
        response.should().respond(403);
    }

    @Test
    public void test_findNames_with_options_should_merge_with_property_provider() throws IOException {
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
    public void test_clean_getAllTasks() throws IOException {
        post("/api/task/batchUpdate/index/file/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), "{}").response();
        taskManager.waitTasksToBeDone(1, SECONDS);
        List<String> taskNames = taskManager.getTasks().map(t -> t.id).toList();

        ShouldChain responseBody = post("/api/task/clean", "{}").should().haveType("application/json");

        responseBody.should().contain(taskNames.get(0));
        responseBody.should().contain(taskNames.get(1));
        assertThat(taskManager.getTasks().findAny());
    }

    @Test
    public void test_clean_task_filter() throws IOException {
        post("/api/task/batchUpdate/index/file/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), "{}").response();

        ShouldChain responseBody = post("/api/task/clean?name=Scan", "{}").should().haveType("application/json");

        responseBody.should().contain("ScanTask");
        responseBody.should().not().contain("IndexTask");
        assertThat(taskManager.getTasks().toList()).hasSize(1);
    }

    @Test
    public void test_clean_one_done_task() throws IOException {
        String dummyTaskId = taskManager.startTask(TestTask.class, User.local(), new HashMap<>());
        taskManager.waitTasksToBeDone(1, SECONDS);
        assertThat(taskManager.getTasks().toList()).hasSize(1);
        assertThat(taskManager.getTask(dummyTaskId).getState()).isEqualTo(Task.State.DONE);

        delete("/api/task/clean/" + dummyTaskId).should().respond(200);

        assertThat(taskManager.getTasks().toList()).hasSize(0);
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
    public void test_cannot_clean_running_task() throws Exception {
        String dummyTaskId = taskManager.startTask(TestSleepingTask.class, User.local(), new HashMap<>());
        assertHasState(dummyTaskId, RUNNING, taskManager, 5000, 100);
        delete("/api/task/clean/" + dummyTaskId).should().respond(403);
        assertThat(taskManager.getTasks().toList()).hasSize(1);
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
        String t1Id = taskManager.startTask(TestSleepingTask.class, User.local(), Map.of("value", 10000));
        String t2Id = taskManager.startTask(TestSleepingTask.class, User.local(), Map.of("value", 10000));
        Response res = put("/api/task/stop").response();
        assertThat(res.code()).isEqualTo(200);

        assertThat(taskManager.getTask(t1Id).getState()).isEqualTo(Task.State.CANCELLED);
        assertThat(taskManager.getTask(t2Id).getState()).isEqualTo(Task.State.CANCELLED);

        assertThat(res.content()).contains(t1Id + "\":true");
        assertThat(res.content()).contains(t2Id + "\":true");
    }
    
    @Test
    public void test_stop_all_filters() throws IOException {
        String t1Id = taskManager.startTask(TestSleepingTask.class, User.local(), new HashMap<>());
        String t2Id = taskManager.startTask(TestAnotherSleepingTask.class, User.local(), new HashMap<>());
        put("/api/task/stop?name=Another").should().respond(200).contain(t2Id + "\":true");

        assertThat(taskManager.getTask(t1Id).getState()).isEqualTo(Task.State.RUNNING);
        assertThat(taskManager.getTask(t2Id).getState()).isEqualTo(Task.State.CANCELLED);

        put("/api/task/stop?name=Sleeping").should().respond(200).contain(t1Id + "\":true");
        assertThat(taskManager.getTask(t1Id).getState()).isEqualTo(Task.State.CANCELLED);
    }

    @Test
    public void test_stop_all_filters_running_getAllTasks() throws IOException {
        taskManager.startTask(TestTask.class, User.local(), new HashMap<>());
        taskManager.waitTasksToBeDone(1, SECONDS);

        put("/api/task/stop").should().respond(200).contain("{}");
    }

    @Test
    public void test_clear_done_getAllTasks() throws IOException {
        taskManager.startTask(TestTask.class, User.local(), new HashMap<>());
        taskManager.waitTasksToBeDone(1, SECONDS);

        put("/api/task/stop").should().respond(200).contain("{}");

        assertThat(taskManager.clearDoneTasks()).hasSize(1);
        assertThat(taskManager.getTasks().toList()).hasSize(0);
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

    @Test
    public void test_create_new_task_with_group() {
        put("/api/task/my_json_task_id?group=Python", String.format("""
            {"@type":"Task","id":"my_json_task_id","name":"%s",
            "arguments": {"user":{"@type":"org.icij.datashare.user.User", "id":"local","name":null,"email":null,"provider":"local","details":{"uid":"local","groups_by_applications":{"datashare":["local-datashare"]}}
            }}}""", TaskCreation.class.getName()))
                .should().respond(201);
    }

    @Test
    public void test_get_datashare_result() throws IOException {
        // Given
        String taskId = taskManager.startTask(TestTask.class, User.local(), Map.of());
        taskManager.waitTasksToBeDone(1, SECONDS);
        // When
        String content = get("/api/task/" + taskId + "/result").response().content();
        int taskRes = MAPPER.readValue(content, int.class);
        // Then
        assertThat(taskRes).isEqualTo(10);
    }

    @Test
    public void test_get_language_agnostic_result() throws IOException {
        // Given
        String taskId = taskManager.startTask(SerializationTestTask.class, User.local(), Map.of());
        taskManager.waitTasksToBeDone(1, SECONDS);
        // When
        String content = get("/api/task/" + taskId + "/result").response().content();
        // Then
        String expectedResult = "{\"value\":10,\"whatever\":{\"any\":\"extra\"}}";
        assertThat(content).isEqualTo(expectedResult);
    }

    @Test
    public void test_task_list_by_unsupported_filter_should_return_400() {
        get("/api/task/all?filter=foo").should().respond(400);
    }

    @Test
    public void test_task_filters_from_context_should_throw_for_unsupported_filter() {
        Request request = new MockRequest(Map.of("unknown", "field"));
        Context ctx = new Context(request, null, null, null, null);
        assertThrows(BadRequestException.class, () -> taskFiltersFromContext(ctx, null));
    }

    @Test
    public void test_task_filters_from_context_should_not_filter() {
        // Given
        Request request = new MockRequest(Map.of());
        Context ctx = new Context(request, null, null, null, null);
        // When
        TaskFilters filters = taskFiltersFromContext(ctx, null);
        // Then
        TaskFilters expectedFilters = TaskFilters.empty().withStates(Set.of()).withArgs(List.of());
        assertThat(filters).isEqualTo(expectedFilters);
    }

    @Test
    public void test_task_filters_from_context_should_filter_task_by_names() {
        // Given
        Request request = new MockRequest(Map.of("name", "someTask|someOtherTask"));
        Context ctx = new Context(request, null, null, null, null);
        // When
        TaskFilters filters = taskFiltersFromContext(ctx, null);
        // Then
        TaskFilters expectedFilters = TaskFilters.empty().withStates(Set.of()).withArgs(List.of())
            .withNames(".*someTask|someOtherTask.*");
        assertThat(filters).isEqualTo(expectedFilters);
    }

    @Test
    public void test_task_filters_from_context_should_filter_task_by_user() {
        // Given
        class MockUser extends User implements net.codestory.http.security.User {
            public MockUser() {
                super("some-id", "some-name", "", "", "{}");
            }
            @Override
            public String login() { return null; }
            @Override
            public String[] roles() { return new String[0];}
        }

        MockUser mockUser = new MockUser();
        Request request = new MockRequest(Map.of());
        Context ctx = new Context(request, null, null, null, null);
        ctx.setCurrentUser(mockUser);
        // When
        TaskFilters filters = taskFiltersFromContext(ctx, null);
        // Then
        TaskFilters expectedFilters = TaskFilters.empty().withStates(Set.of()).withArgs(List.of())
            .withUser(mockUser);
        assertThat(filters).isEqualTo(expectedFilters);
    }

    @Test
    public void test_task_filters_from_context_should_filter_task_by_args() {
        // Given
        Request request = new MockRequest(Map.of("args.nested.attribute", "someregex"));
        Context ctx = new Context(request, null, null, null, null);
        // When
        TaskFilters filters = taskFiltersFromContext(ctx, null);
        // Then
        TaskFilters expectedFilters = TaskFilters.empty().withStates(Set.of())
            .withArgs(List.of(new TaskFilters.ArgsFilter("nested.attribute", ".*someregex.*")));
        assertThat(filters).isEqualTo(expectedFilters);
    }

    @Test
    public void test_task_filters_from_context_should_filter_task_by_state() {
        // Given
        Request request = new MockRequest(Map.of("state", "DONE"));
        Context ctx = new Context(request, null, null, null, null);
        // When
        TaskFilters filters = taskFiltersFromContext(ctx, null);
        // Then
        TaskFilters expectedFilters = TaskFilters.empty().withStates(Set.of(DONE)).withArgs(List.of());
        assertThat(filters).isEqualTo(expectedFilters);
    }

    @NotNull
    private Map<String, Object> getDefaultProperties() {
        Map<String, Object> map = new HashMap<>(Map.of(
            "dataDir", "/default/data/dir",
            "foo", "bar",
            "batchDownloadDir", "app/tmp",
            "defaultProject", "local-datashare",
            "queueName", "extract:queue",
            "reportName", "extract:report:local-datashare",
            "digestProjectName", "local-datashare"
        ));
        // Override the queueName with
        map.put("queueName", new PropertiesProvider(PropertiesProvider.fromMap(map)).queueNameWithHash());
        return map;
    }

    @NotNull
    private PropertiesProvider getDefaultPropertiesProvider() {
        return new PropertiesProvider(getDefaultProperties());
    }

    private Optional<Task> findTask(TaskManagerMemory taskManager, String expectedName) throws IOException {
        return taskManager.getTasks().filter(t -> expectedName.equals(t.name)).findFirst();
    }

    private void assertHasState(String taskId, Task.State expectedState, TaskManager taskManager, int timeoutMs, int pollIntervalMs)
        throws IOException, InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (taskManager.getTask(taskId).getState() == expectedState) {
                return;
            }
            Thread.sleep(pollIntervalMs);
        }
        String msg = "failed to get state " + expectedState + " for task " + taskId + " in less than " + timeoutMs + "ms";
        throw new AssertionError(msg);
    }


    static class MockRequest implements Request {
        private final MockQueryImpl query;
        MockRequest(Map<String, String> query) {
            this.query = new MockQueryImpl(query);
        }

        @Override
        public Query query() {
        return this.query;
        }

        @Override
        public String uri() {return null;}

        @Override
        public String method() { return null;}

        @Override
        public String content() throws IOException { return null;}

        @Override
        public String contentType() { return null;}

        @Override
        public InputStream inputStream() { return null;}

        @Override
        public List<String> headerNames() { return null;}

        @Override
        public List<String> headers(String name) { return null;}

        @Override
        public String header(String name) { return null; }

        @Override
        public InetSocketAddress clientAddress() { return null; }

        @Override
        public boolean isSecure() {return true;}

        @Override
        public Cookies cookies() { return null; }


        @Override
        public List<Part> parts() { return null; }

        @Override
        public <T> T unwrap(Class<T> type) { return null; }
    }

    static class MockQueryImpl implements Query {
        Map<String, String> query;

        MockQueryImpl(Map<String, String> query) {
            this.query = query;
        }

        @Override
        public Collection<String> keys() {
            return query.keySet();
        }

        @Override
        public Iterable<String> all(String s) {
            return Optional.ofNullable(this.query.get(s)).stream().toList();
        }

        @Override
        public <T> T unwrap(Class<T> aClass) {
            return null;
        }
    }
}
