package org.icij.datashare.asynctasks;

import java.util.function.Function;

public class TaskInspector {
    private final int pollIntervalMs;
    private final TaskManager taskManager;

    public TaskInspector(TaskManager taskManager) {
        this.taskManager = taskManager;
        this.pollIntervalMs = 10;
    }

    public TaskInspector(TaskManager taskManager, int pollIntervalMs) {
        this.taskManager = taskManager;
        this.pollIntervalMs = pollIntervalMs;
    }

    public void awaitToBeStarted(String taskId) {
        this.awaitPredicate(taskId, null, (TaskView<?> t) -> (t.getState().compareTo(
            TaskView.State.RUNNING) > 0));
    }

    public void awaitToBeStarted(String taskId, int timeoutMs) {
        this.awaitPredicate(taskId, timeoutMs, (TaskView<?> t) -> (t.getState().compareTo(TaskView.State.RUNNING) >= 0));
    }

    public void awaitStatus(String taskId, TaskView.State state) {
        this.awaitPredicate(taskId, null, (TaskView<?> t) -> (t.getState() == state));
    }

    public void awaitStatus(String taskId, TaskView.State state, Integer timeoutMs) {
        this.awaitPredicate(taskId, timeoutMs, (TaskView<?> t) -> (t.getState() == state));
    }

    public void awaitPredicate(String taskId, Integer timeoutMs, Function<TaskView<?>, Boolean> taskPredicate) {
        TestUtils.awaitPredicate(timeoutMs, pollIntervalMs, () -> taskPredicate.apply(this.taskManager.getTask(taskId)));
    }
}
