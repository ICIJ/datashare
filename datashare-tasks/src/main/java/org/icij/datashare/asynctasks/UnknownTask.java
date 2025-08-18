package org.icij.datashare.asynctasks;

public class UnknownTask extends RuntimeException {
    final String taskId;

    public UnknownTask(String taskId) {
        super("Unknown task " + taskId);
        this.taskId = taskId;
    }

    public UnknownTask(String taskId, Throwable cause) {
        super("Unknown task " + taskId, cause);
        this.taskId = taskId;
    }
}
