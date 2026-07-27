package org.icij.datashare;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.TaskManagerMemory;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.asynctasks.TaskResult;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.tasks.IndexTask;
import org.icij.datashare.tasks.PipelineTask;
import org.icij.datashare.tasks.ScanTask;
import org.icij.datashare.user.User;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.EnumSet;
import java.util.HashMap;
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
import static org.mockito.Mockito.times;
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
        assertThat(args.getValue().get(PipelineTask.UPSTREAM_TASK_ID)).isEqualTo("id-scan");
        // the first stage has no producer to wait for
        ArgumentCaptor<Map<String, Object>> scanArgs = ArgumentCaptor.forClass(Map.class);
        verify(mockedManager).startTask(eq(ScanTask.class), any(), scanArgs.capture());
        assertThat(scanArgs.getValue().containsKey(PipelineTask.UPSTREAM_TASK_ID)).isFalse();
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

    private static Task<Long> doneTask() {
        Task<Long> task = new Task<>(ScanTask.class.getName(), User.local(), new HashMap<>());
        task.setResult(new TaskResult<>(0L));
        return task;
    }
}
