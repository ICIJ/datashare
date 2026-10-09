package org.icij.datashare.web;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskFilters;
import org.icij.datashare.asynctasks.TaskManagerMemory;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.model.TargetModel;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.CasbinRuleAdapter;
import org.icij.datashare.policies.MappingPolicy;
import org.icij.datashare.policies.MappingPolicyAnnotation;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.tabular.ExtractionMapping;
import org.icij.datashare.tabular.ExtractionMappingRepository;
import org.icij.datashare.tabular.ExtractionMappingServiceImpl;
import org.icij.datashare.tabular.InvalidExtractionMapping;
import org.icij.datashare.tabular.RowSourceOptions;
import org.icij.datashare.tabular.UnreadableExtractionMapping;
import org.icij.datashare.tasks.StructuredEntityExtractionTask;
import org.icij.datashare.tasks.TestTaskUtils;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.cli.DatashareCliOptions.TASK_MANAGER_POLLING_INTERVAL_OPT;
import static org.icij.datashare.text.Project.project;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

public class ExtractionMappingResourceTest extends AbstractProdWebServerTest {
    private static final String BODY = """
            {"name": "companies", "model": "ftm", "documentId": "docId",
             "entities": {"c": {"type": "Company", "keys": ["id"], "properties": {"name": {"columns": ["name"]}}}}}
            """;
    private static final TestTaskUtils.DatashareTaskFactoryForTest taskFactory = mock(TestTaskUtils.DatashareTaskFactoryForTest.class);
    private static final TaskRepositoryMemory taskRepository = new TaskRepositoryMemory();
    private static final TaskManagerMemory taskManager = new TaskManagerMemory(taskFactory, taskRepository,
            new PropertiesProvider(Map.of(TASK_MANAGER_POLLING_INTERVAL_OPT, "500")), new CountDownLatch(1));
    @Mock
    JooqRepository jooqRepository;
    @Mock
    ExtractionMappingRepository mappings;
    private AutoCloseable mocks;

    @Before
    public void setUp() {
        mocks = openMocks(this);
        when(jooqRepository.getProjects()).thenReturn(List.of(project("prj")));
        LocalUserFilter localUserFilter = new LocalUserFilter(new PropertiesProvider(), jooqRepository);
        configure(routes -> routes.add(new ExtractionMappingResource(new ExtractionMappingServiceImpl(mappings, taskManager))).filter(localUserFilter));
        TestTaskUtils.init(taskFactory);
    }

    @After
    public void tearDown() throws Exception {
        taskManager.stopTasks(new TaskFilters());
        taskManager.awaitTermination(2, SECONDS);
        taskManager.clear();
        taskRepository.clear();
        mocks.close();
    }

    @Test
    public void test_save_creates_the_mapping_under_the_path_project_and_id() {
        when(mappings.save(any())).thenReturn(true);

        put("/api/prj/extraction-mappings/m1", BODY).should().respond(201);

        ArgumentCaptor<ExtractionMapping> saved = ArgumentCaptor.forClass(ExtractionMapping.class);
        verify(mappings).save(saved.capture());
        assertThat(saved.getValue().id()).isEqualTo("m1");
        assertThat(saved.getValue().projectId()).isEqualTo("prj");
    }

    @Test
    public void test_save_takes_the_owner_from_the_session_not_the_body() {
        when(mappings.save(any())).thenReturn(true);
        String body = BODY.replaceFirst("\\{", "{\"userId\": \"someone\", \"projectId\": \"other\", \"id\": \"m9\", ");

        put("/api/prj/extraction-mappings/m1", body).should().respond(201);

        ArgumentCaptor<ExtractionMapping> saved = ArgumentCaptor.forClass(ExtractionMapping.class);
        verify(mappings).save(saved.capture());
        assertThat(saved.getValue().userId()).isEqualTo("local");
        assertThat(saved.getValue().projectId()).isEqualTo("prj");
        assertThat(saved.getValue().id()).isEqualTo("m1");
    }

    @Test
    public void test_save_reads_a_json_body_without_charset_as_utf8() throws Exception {
        when(mappings.save(any())).thenReturn(true);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port() + "/api/prj/extraction-mappings/m1"))
                                         .header("Content-Type", "application/json")
                                         .PUT(HttpRequest.BodyPublishers.ofString(BODY.replace("\"name\"]", "\"Société\"]"), UTF_8))
                                         .build();

        assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(201);

        ArgumentCaptor<ExtractionMapping> saved = ArgumentCaptor.forClass(ExtractionMapping.class);
        verify(mappings).save(saved.capture());
        assertThat(saved.getValue().entities().get("c").properties().get("name").columns()).containsOnly("Société");
    }

    @Test
    public void test_save_reports_an_existing_id_as_a_conflict() {
        when(mappings.save(any())).thenReturn(false);

        put("/api/prj/extraction-mappings/m1", BODY).should().respond(409);
    }

    @Test
    public void test_save_reports_an_invalid_mapping() {
        when(mappings.save(any())).thenThrow(
                new InvalidExtractionMapping("m1", List.of(new TargetModel.Violation("unknown property 'nope'"))));

        put("/api/prj/extraction-mappings/m1", BODY).should().respond(400).contain("unknown property 'nope'");
    }

    @Test
    public void test_save_rejects_a_body_that_is_not_an_object() {
        put("/api/prj/extraction-mappings/m1", "[1, 2]").should().respond(400);
        verify(mappings, never()).save(any());
    }

    @Test
    public void test_save_rejects_malformed_json() {
        put("/api/prj/extraction-mappings/m1", "{not json").should().respond(400);
        verify(mappings, never()).save(any());
    }

    @Test
    public void test_save_rejects_a_mapping_missing_a_required_field() {
        put("/api/prj/extraction-mappings/m1", "{\"model\": \"ftm\"}").should().respond(400);
        verify(mappings, never()).save(any());
    }

    @Test
    public void test_run_starts_the_extraction_task() throws Exception {
        when(mappings.get("prj", "m1")).thenReturn(Optional.of(mapping()));

        post("/api/task/structuredEntityExtraction/prj/m1").should().respond(201).contain("taskId");

        Task<?> task = taskManager.getTasks()
                                  .filter(t -> StructuredEntityExtractionTask.class.getName().equals(t.name))
                                  .findFirst().orElseThrow();
        assertThat(task.args).includes(entry("defaultProject", "prj"), entry("mappingId", "m1"));
    }

    @Test
    public void test_run_reports_an_unknown_mapping() {
        when(mappings.get("prj", "m1")).thenReturn(Optional.empty());

        post("/api/task/structuredEntityExtraction/prj/m1").should().respond(404);
    }

    @Test
    public void test_save_refuses_a_misspelled_field_by_name() {
        String misspelled = BODY.replace("\"documentId\": \"docId\"", "\"documentId\": \"docId\", \"rootID\": \"zip\"");

        put("/api/prj/extraction-mappings/m1", misspelled).should().respond(400).contain("unknown field 'rootID'");
        verify(mappings, never()).save(any());
    }

    @Test
    public void test_run_forbids_a_user_the_project_is_not_granted_to() throws Exception {
        when(jooqRepository.getProjects()).thenReturn(new ArrayList<>());
        when(mappings.get("prj", "m1")).thenReturn(Optional.of(mapping()));

        // the task refuses such a user, so answering 201 would hand out the id of a run bound to fail
        post("/api/task/structuredEntityExtraction/prj/m1").should().respond(403);
        assertThat(taskManager.getTasks().count()).isEqualTo(0);
    }

    @Test
    public void test_run_reports_an_unreadable_mapping_as_a_conflict() {
        when(mappings.get("prj", "m1")).thenThrow(new UnreadableExtractionMapping("m1", new IOException("unknown model")));

        post("/api/task/structuredEntityExtraction/prj/m1").should().respond(409).contain("could not be read");
    }

    @Test
    public void test_run_is_guarded_by_the_mapping_policy() throws Exception {
        when(mappings.get("prj", "m1")).thenReturn(Optional.of(mapping()));
        MappingPolicyAnnotation policy = new MappingPolicyAnnotation(new Authorizer(mock(CasbinRuleAdapter.class)),
                                                                    new ExtractionMappingServiceImpl(mappings, taskManager));
        LocalUserFilter localUserFilter = new LocalUserFilter(new PropertiesProvider(), jooqRepository);
        configure(routes -> routes.registerAroundAnnotation(MappingPolicy.class, policy)
                                  .add(new ExtractionMappingResource(new ExtractionMappingServiceImpl(mappings, taskManager))).filter(localUserFilter));

        // the local user holds no role on the project in this authorizer
        post("/api/task/structuredEntityExtraction/prj/m1").should().respond(403);
    }

    private static ExtractionMapping mapping() {
        return new ExtractionMapping("m1", "prj", "local", "companies", "ftm", "docId", null, RowSourceOptions.defaults(),
                Map.of("c", new ExtractionMapping.EntityMapping("Company", List.of("id"),
                        Map.of("name", new ExtractionMapping.PropertyMapping(List.of("name"), null, null, null, null)))));
    }
}
