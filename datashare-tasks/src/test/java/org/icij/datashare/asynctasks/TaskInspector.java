package org.icij.datashare.asynctasks;

import java.util.concurrent.TimeUnit;

public class TaskInspector {
    private final TaskManager taskManager;

    public TaskInspector(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    public boolean awaitToBeStarted(String taskId, int timeoutMs) throws Exception {
        return this.awaitStatus(taskId, Task.State.RUNNING, timeoutMs, TimeUnit.MILLISECONDS);
    }

    public boolean awaitStatus(String taskId, Task.State state, long timeout, TimeUnit unit) throws Exception {
        StateLatch stateLatch = new StateLatch();
        taskManager.setLatch(taskId, stateLatch);
        return stateLatch.await(state, timeout, unit);
    }
}
