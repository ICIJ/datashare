package org.icij.datashare;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskManagerMemory;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.tasks.ScanTask;
import org.icij.datashare.user.User;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static java.util.Collections.*;
import static org.icij.datashare.cli.DatashareCliOptions.TASK_MANAGER_POLLING_INTERVAL_OPT;
import static org.mockito.Mockito.mock;

public class CliAppTest {
    private final TaskRepositoryMemory taskRepository = new TaskRepositoryMemory();
    private final TaskManagerMemory taskManager = new TaskManagerMemory(
            mock(DatashareTaskFactory.class), taskRepository,
            new PropertiesProvider(Map.of(TASK_MANAGER_POLLING_INTERVAL_OPT, "100")),
            new CountDownLatch(1));

    @Test(timeout = 2000)
    public void test_await_tasks_ignores_stale_tasks_in_repo() throws Exception {
        // GIVEN
        Task<Long> staleTask = new Task<>(ScanTask.class.getName(), User.local(), new HashMap<>());
        staleTask.setProgress(0.5);
        taskRepository.insert(staleTask, null);

        // GIVEN
        String taskId = taskManager.startTask(ScanTask.class.getName(), User.local(), new HashMap<>());
        taskManager.getTask(taskId).setResult(new org.icij.datashare.asynctasks.TaskResult<>(0L));

        // WHEN/THEN
        CliApp.awaitTasks(taskManager, singletonList(taskId));
    }
}
