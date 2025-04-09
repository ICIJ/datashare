package org.icij.datashare.asynctasks;

public class UnknownTask extends RuntimeException {
    final String taskId;

    public UnknownTask(String taskId) {
        this.taskId = taskId;
    }

    public UnknownTask(String taskId, Throwable cause) {
        super(cause);
        this.taskId = taskId;
    }

    public UnknownTask(String taskId, String message) {
        super(message);
        this.taskId = taskId;
    }
}
