package org.icij.datashare;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.TaskManagerMemory;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.asynctasks.TaskResult;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.model.TargetModel;
import org.icij.datashare.tabular.DuplicateExtractionMapping;
import org.icij.datashare.tabular.ExtractionMapping;
import org.icij.datashare.tabular.ExtractionMappingService;
import org.icij.datashare.tabular.InvalidExtractionMapping;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.tasks.IndexTask;
import org.icij.datashare.tasks.UpstreamGate;
import org.icij.datashare.tasks.ScanTask;
import org.icij.datashare.user.User;
import org.jooq.exception.DataAccessException;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.NEXT_STAGE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.TASK_MANAGER_POLLING_INTERVAL_OPT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class CliAppTest {
    private final TaskRepositoryMemory taskRepository = new TaskRepositoryMemory();
    private final TaskManagerMemory taskManager = new TaskManagerMemory(
            mock(DatashareTaskFactory.class), taskRepository,
            new PropertiesProvider(Map.of(TASK_MANAGER_POLLING_INTERVAL_OPT, "100")),
            new CountDownLatch(1));
    private static final String MAPPING_JSON = """
            {"id": "m1", "projectId": "from-file", "userId": "someone", "name": "companies", "model": "ftm",
             "documentId": "docId",
             "entities": {"c": {"type": "Company", "keys": ["id"], "properties": {"name": {"columns": ["name"]}}}}}
            """;

    @Test
    public void test_save_mapping_file_saves_it_under_the_cli_project_with_no_owner() throws Exception {
        ExtractionMappingService mappings = mock(ExtractionMappingService.class);
        Properties properties = entitiesProperties(Files.writeString(Files.createTempFile("mapping", ".json"), MAPPING_JSON).toString());

        assertThat(saveMappingFile(mappings, properties)).isEqualTo(CliApp.EXIT_SUCCESS);

        ArgumentCaptor<ExtractionMapping> saved = ArgumentCaptor.forClass(ExtractionMapping.class);
        verify(mappings).saveIfIdentical(saved.capture());
        assertThat(saved.getValue().id()).isEqualTo("m1");
        assertThat(saved.getValue().projectId()).isEqualTo("prj");
        assertThat(saved.getValue().userId()).isNull();
        assertThat(properties.getProperty("mappingId")).isEqualTo("m1");
    }

    @Test
    public void test_save_mapping_file_reports_an_existing_id_as_a_conflict() throws Exception {
        ExtractionMappingService mappings = mock(ExtractionMappingService.class);
        doThrow(new DuplicateExtractionMapping("prj", "m1")).when(mappings).saveIfIdentical(any());
        Properties properties = entitiesProperties(Files.writeString(Files.createTempFile("mapping", ".json"), MAPPING_JSON).toString());

        assertThat(saveMappingFile(mappings, properties)).isEqualTo(CliApp.EXIT_CONFLICT);
        assertThat(properties.containsKey("mappingId")).isFalse();
    }

    @Test
    public void test_save_mapping_file_reports_an_invalid_mapping() throws Exception {
        ExtractionMappingService mappings = mock(ExtractionMappingService.class);
        doThrow(new InvalidExtractionMapping("m1", List.of(new TargetModel.Violation("unknown property")))).when(mappings).saveIfIdentical(any());
        Properties properties = entitiesProperties(Files.writeString(Files.createTempFile("mapping", ".json"), MAPPING_JSON).toString());

        assertThat(saveMappingFile(mappings, properties)).isEqualTo(CliApp.EXIT_VALIDATION);
    }

    @Test
    public void test_save_mapping_file_reports_a_file_that_is_not_a_mapping() throws Exception {
        ExtractionMappingService mappings = mock(ExtractionMappingService.class);
        Properties properties = entitiesProperties(Files.writeString(Files.createTempFile("mapping", ".json"), "[1, 2]").toString());

        assertThat(saveMappingFile(mappings, properties)).isEqualTo(CliApp.EXIT_VALIDATION);
        verify(mappings, never()).saveIfIdentical(any());
    }

    @Test
    public void test_save_mapping_file_reports_a_missing_file() throws Exception {
        ExtractionMappingService mappings = mock(ExtractionMappingService.class);

        assertThat(saveMappingFile(mappings, entitiesProperties("/does/not/exist.json"))).isEqualTo(CliApp.EXIT_VALIDATION);
        verify(mappings, never()).saveIfIdentical(any());
    }

    @Test
    public void test_save_mapping_file_requires_the_option_with_entities() {
        assertThat(saveMappingFile(mock(ExtractionMappingService.class), entitiesProperties(null))).isEqualTo(CliApp.EXIT_VALIDATION);
    }

    @Test
    public void test_save_mapping_file_reports_a_database_failure_as_a_runtime_error() throws Exception {
        ExtractionMappingService mappings = mock(ExtractionMappingService.class);
        doThrow(new DataAccessException("connection refused")).when(mappings).saveIfIdentical(any());

        assertThat(saveMappingFile(mappings, entitiesProperties(mappingFile(MAPPING_JSON)))).isEqualTo(CliApp.EXIT_RUNTIME);
    }

    @Test
    public void test_save_mapping_file_refuses_a_misspelled_field() throws Exception {
        ExtractionMappingService mappings = mock(ExtractionMappingService.class);
        String misspelled = MAPPING_JSON.replace("\"documentId\": \"docId\"", "\"documentId\": \"docId\", \"rootID\": \"zip\"");

        assertThat(saveMappingFile(mappings, entitiesProperties(mappingFile(misspelled)))).isEqualTo(CliApp.EXIT_VALIDATION);
        verify(mappings, never()).saveIfIdentical(any());
    }

    @Test
    public void test_save_mapping_file_rejects_the_option_without_entities() throws Exception {
        ExtractionMappingService mappings = mock(ExtractionMappingService.class);
        Properties properties = entitiesProperties(mappingFile(MAPPING_JSON));
        properties.setProperty("stages", "SCAN,INDEX");

        // nothing but the ENTITIES stage reads the file, so without it the mapping would be dropped in silence
        assertThat(saveMappingFile(mappings, properties)).isEqualTo(CliApp.EXIT_VALIDATION);
        verifyNoInteractions(mappings);
    }

    @Test
    public void test_save_mapping_file_does_nothing_without_entities() {
        ExtractionMappingService mappings = mock(ExtractionMappingService.class);
        Properties properties = new Properties();
        properties.setProperty("stages", "SCAN,INDEX");

        assertThat(saveMappingFile(mappings, properties)).isEqualTo(CliApp.EXIT_SUCCESS);
        verifyNoInteractions(mappings);
    }

    private static Properties entitiesProperties(String mappingFile) {
        Properties properties = new Properties();
        properties.setProperty("stages", "ENTITIES");
        properties.setProperty("defaultProject", "prj");
        if (mappingFile != null) {
            properties.setProperty("mappingFile", mappingFile);
        }
        return properties;
    }

    private static String mappingFile(String json) throws IOException {
        return Files.writeString(Files.createTempFile("mapping", ".json"), json).toString();
    }

    private static int saveMappingFile(ExtractionMappingService mappings, Properties properties) {
        return CliApp.saveMappingFile(mappings, new PipelineHelper(new PropertiesProvider(properties)), properties);
    }

    @Test(timeout = 2000)
    public void test_await_termination_with_scope_ignores_stale_tasks_in_repo() throws Exception {
        // GIVEN
        Task<Long> staleTask = new Task<>(ScanTask.class.getName(), User.local(), new HashMap<>());
        staleTask.setProgress(0.5);
        taskRepository.insert(staleTask, null);

        // GIVEN
        String taskId = taskManager.startTask(ScanTask.class.getName(), User.local(), new HashMap<>());
        taskManager.getTask(taskId).setResult(new TaskResult<>(0L));

        // WHEN/THEN
        assertThat(taskManager.awaitTermination(1, TimeUnit.SECONDS, Set.of(taskId))).isTrue();
    }

    @Test
    public void test_run_pipeline_starts_every_stage_then_awaits_them_all_at_once() throws Exception {
        TaskManager mockedManager = mock(TaskManager.class);
        when(mockedManager.startTask(eq(ScanTask.class), any(), any())).thenReturn("id-scan");
        when(mockedManager.startTask(eq(IndexTask.class), any(), any())).thenReturn("id-index");
        doReturn(doneTask()).when(mockedManager).getTask("id-scan");
        doReturn(doneTask()).when(mockedManager).getTask("id-index");
        Properties properties = new Properties();
        properties.setProperty("stages", "INDEX,SCAN");

        assertThat(CliApp.runPipeline(mockedManager, new PipelineHelper(new PropertiesProvider(properties)), properties)).isTrue();

        // stages overlap now: awaiting each one before starting the next would let a bounded queue
        // deadlock a large corpus, the upstream-task gate is what makes termination correct
        InOrder inOrder = inOrder(mockedManager);
        inOrder.verify(mockedManager).startTask(eq(ScanTask.class), any(), any());
        inOrder.verify(mockedManager).startTask(eq(IndexTask.class), any(), any());
        inOrder.verify(mockedManager).awaitTermination(anyInt(), any(), eq(Set.of("id-scan", "id-index")));
        verify(mockedManager, times(1)).awaitTermination(anyInt(), any(), any());
    }

    @Test
    public void test_run_pipeline_passes_the_previous_stage_task_id_to_the_next_stage() throws Exception {
        TaskManager mockedManager = mock(TaskManager.class);
        when(mockedManager.startTask(eq(ScanTask.class), any(), any())).thenReturn("id-scan");
        when(mockedManager.startTask(eq(IndexTask.class), any(), any())).thenReturn("id-index");
        doReturn(doneTask()).when(mockedManager).getTask("id-scan");
        doReturn(doneTask()).when(mockedManager).getTask("id-index");
        Properties properties = new Properties();
        properties.setProperty("stages", "SCAN,INDEX");

        CliApp.runPipeline(mockedManager, new PipelineHelper(new PropertiesProvider(properties)), properties);

        // this id is how IndexTask knows to keep polling while the scan is still enqueuing
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(mockedManager).startTask(eq(IndexTask.class), any(), args.capture());
        assertThat(args.getValue().get(UpstreamGate.UPSTREAM_TASK_ID)).isEqualTo("id-scan");
        // the first stage has no producer to wait for
        ArgumentCaptor<Map<String, Object>> scanArgs = ArgumentCaptor.forClass(Map.class);
        verify(mockedManager).startTask(eq(ScanTask.class), any(), scanArgs.capture());
        assertThat(scanArgs.getValue().containsKey(UpstreamGate.UPSTREAM_TASK_ID)).isFalse();
    }

    @Test
    public void test_run_pipeline_reports_a_stage_that_does_not_complete() throws Exception {
        TaskManager mockedManager = mock(TaskManager.class);
        when(mockedManager.startTask(eq(ScanTask.class), any(), any())).thenReturn("id-scan");
        when(mockedManager.startTask(eq(IndexTask.class), any(), any())).thenReturn("id-index");
        Task<Long> failed = new Task<>(ScanTask.class.getName(), User.local(), new HashMap<>());
        failed.setError(new TaskError(new RuntimeException("boom")));
        doReturn(failed).when(mockedManager).getTask("id-scan");
        doReturn(doneTask()).when(mockedManager).getTask("id-index");
        Properties properties = new Properties();
        properties.setProperty("stages", "SCAN,INDEX");

        // false is what makes the launcher exit non-zero instead of looking like a success
        assertThat(CliApp.runPipeline(mockedManager, new PipelineHelper(new PropertiesProvider(properties)), properties)).isFalse();
    }

    @Test
    public void test_run_pipeline_runs_a_repeated_stage_only_once() throws Exception {
        TaskManager mockedManager = mock(TaskManager.class);
        when(mockedManager.startTask(eq(ScanTask.class), any(), any())).thenReturn("id-scan");
        when(mockedManager.startTask(eq(IndexTask.class), any(), any())).thenReturn("id-index");
        doReturn(doneTask()).when(mockedManager).getTask("id-scan");
        doReturn(doneTask()).when(mockedManager).getTask("id-index");
        Properties properties = new Properties();
        properties.setProperty("stages", "SCAN,SCAN,INDEX");

        assertThat(CliApp.runPipeline(mockedManager, new PipelineHelper(new PropertiesProvider(properties)), properties)).isTrue();

        // twice would walk and enqueue the whole data dir a second time
        verify(mockedManager).startTask(eq(ScanTask.class), any(), any());
    }

    @Test
    public void test_task_classes_maps_every_stage_to_its_task_class() {
        // asserted against the enum, so a stage added without a task class fails here
        assertThat(EnumSet.copyOf(CliApp.TASK_CLASSES.keySet())).isEqualTo(EnumSet.complementOf(EnumSet.of(Stage.BATCHNLP)));
    }

    @Test
    public void test_next_stage_is_rejected_when_enqueueidx_is_not_in_stages() {
        // nothing but EnqueueFromIndexTask reads nextStage, so without that stage it would be dropped in silence
        assertThat(validateNextStage("SCANIDX,INDEX", "NLP")).isEqualTo(CliApp.EXIT_VALIDATION);
    }

    @Test
    public void test_next_stage_is_rejected_when_the_stage_is_unknown() {
        assertThat(validateNextStage("ENQUEUEIDX", "NOPE")).isEqualTo(CliApp.EXIT_VALIDATION);
    }

    @Test
    public void test_next_stage_is_rejected_when_no_task_drains_its_queue() {
        assertThat(validateNextStage("ENQUEUEIDX", "INDEX")).isEqualTo(CliApp.EXIT_VALIDATION);
    }

    @Test
    public void test_next_stage_is_accepted_for_a_queue_consuming_stage() {
        assertThat(validateNextStage("ENQUEUEIDX", "ARTIFACT")).isEqualTo(CliApp.EXIT_SUCCESS);
        assertThat(validateNextStage("ENQUEUEIDX", "LANGUAGE")).isEqualTo(CliApp.EXIT_SUCCESS);
    }

    @Test
    public void test_stages_without_next_stage_are_accepted() {
        assertThat(validateNextStage("SCANIDX,INDEX", null)).isEqualTo(CliApp.EXIT_SUCCESS);
        assertThat(validateNextStage("ENQUEUEIDX", null)).isEqualTo(CliApp.EXIT_SUCCESS);
    }

    @Test
    public void test_stages_chain_is_rejected_when_no_task_drains_the_stage_it_enqueues_for() {
        // without nextStage the enqueuing task takes the next stage from the chain, which strands
        // every document it enqueues when that stage reads the index instead of a queue
        assertThat(validateNextStage("ENQUEUEIDX,BATCHNLP", null)).isEqualTo(CliApp.EXIT_VALIDATION);
    }

    private static int validateNextStage(String stages, String nextStage) {
        Properties properties = new Properties();
        properties.setProperty(PipelineHelper.STAGES_OPT, stages);
        if (nextStage != null) {
            properties.setProperty(NEXT_STAGE_OPT, nextStage);
        }
        return CliApp.validateNextStage(new PipelineHelper(new PropertiesProvider(properties)), properties);
    }

    private static Task<Long> doneTask() {
        Task<Long> task = new Task<>(ScanTask.class.getName(), User.local(), new HashMap<>());
        task.setResult(new TaskResult<>(0L));
        return task;
    }
}
