package org.icij.datashare;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.TaskManagerMemory;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.asynctasks.TaskResult;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.tasks.ArtifactTask;
import org.icij.datashare.tasks.CategorizeTask;
import org.icij.datashare.tasks.CreateNlpBatchesFromIndex;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.tasks.DeduplicateTask;
import org.icij.datashare.tasks.EnqueueFromIndexTask;
import org.icij.datashare.tasks.ExtractNlpTask;
import org.icij.datashare.tasks.IndexTask;
import org.icij.datashare.tasks.ScanIndexTask;
import org.icij.datashare.tasks.ScanTask;
import org.icij.datashare.user.User;
import org.junit.Test;
import org.mockito.InOrder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.TASK_MANAGER_POLLING_INTERVAL_OPT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CliAppTest {
    private final TaskRepositoryMemory taskRepository = new TaskRepositoryMemory();
    private final TaskManagerMemory taskManager = new TaskManagerMemory(
            mock(DatashareTaskFactory.class), taskRepository,
            new PropertiesProvider(Map.of(TASK_MANAGER_POLLING_INTERVAL_OPT, "100")),
            new CountDownLatch(1));

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
    public void test_run_pipeline_starts_a_stage_only_after_the_previous_one_is_done() throws Exception {
        TaskManager mockedManager = mock(TaskManager.class);
        when(mockedManager.startTask(eq(ScanTask.class), any(), any())).thenReturn("id-scan");
        when(mockedManager.startTask(eq(IndexTask.class), any(), any())).thenReturn("id-index");
        doReturn(doneTask()).when(mockedManager).getTask("id-scan");
        doReturn(doneTask()).when(mockedManager).getTask("id-index");
        Properties properties = new Properties();
        properties.setProperty("stages", "INDEX,SCAN");

        CliApp.runPipeline(mockedManager, new PipelineHelper(new PropertiesProvider(properties)), properties);

        InOrder inOrder = inOrder(mockedManager);
        inOrder.verify(mockedManager).startTask(eq(ScanTask.class), any(), any());
        inOrder.verify(mockedManager).awaitTermination(anyInt(), any(), eq(Set.of("id-scan")));
        inOrder.verify(mockedManager).startTask(eq(IndexTask.class), any(), any());
        inOrder.verify(mockedManager).awaitTermination(anyInt(), any(), eq(Set.of("id-index")));
    }

    @Test
    public void test_run_pipeline_stops_when_a_stage_does_not_complete() throws Exception {
        TaskManager mockedManager = mock(TaskManager.class);
        when(mockedManager.startTask(eq(ScanTask.class), any(), any())).thenReturn("id-scan");
        Task<Long> failed = new Task<>(ScanTask.class.getName(), User.local(), new HashMap<>());
        failed.setError(new TaskError(new RuntimeException("boom")));
        doReturn(failed).when(mockedManager).getTask("id-scan");
        Properties properties = new Properties();
        properties.setProperty("stages", "SCAN,INDEX");

        CliApp.runPipeline(mockedManager, new PipelineHelper(new PropertiesProvider(properties)), properties);

        verify(mockedManager, never()).startTask(eq(IndexTask.class), any(), any());
    }

    @Test
    public void test_task_classes_maps_every_stage_to_its_task_class() {
        assertThat(CliApp.TASK_CLASSES.get(Stage.SCAN)).isEqualTo(ScanTask.class);
        assertThat(CliApp.TASK_CLASSES.get(Stage.SCANIDX)).isEqualTo(ScanIndexTask.class);
        assertThat(CliApp.TASK_CLASSES.get(Stage.DEDUPLICATE)).isEqualTo(DeduplicateTask.class);
        assertThat(CliApp.TASK_CLASSES.get(Stage.INDEX)).isEqualTo(IndexTask.class);
        assertThat(CliApp.TASK_CLASSES.get(Stage.ENQUEUEIDX)).isEqualTo(EnqueueFromIndexTask.class);
        assertThat(CliApp.TASK_CLASSES.get(Stage.CATEGORIZE)).isEqualTo(CategorizeTask.class);
        assertThat(CliApp.TASK_CLASSES.get(Stage.CREATENLPBATCHESFROMIDX)).isEqualTo(CreateNlpBatchesFromIndex.class);
        assertThat(CliApp.TASK_CLASSES.get(Stage.NLP)).isEqualTo(ExtractNlpTask.class);
        assertThat(CliApp.TASK_CLASSES.get(Stage.ARTIFACT)).isEqualTo(ArtifactTask.class);
        assertThat(CliApp.TASK_CLASSES.get(Stage.BATCHNLP)).isNull();
    }

    private static Task<Long> doneTask() {
        Task<Long> task = new Task<>(ScanTask.class.getName(), User.local(), new HashMap<>());
        task.setResult(new TaskResult<>(0L));
        return task;
    }
}
