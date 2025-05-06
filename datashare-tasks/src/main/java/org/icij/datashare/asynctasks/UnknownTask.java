package org.icij.datashare.asynctasks;

public class UnknownTask extends RuntimeException {
    final String taskId;

    public UnknownTask(String taskId) {
        this(taskId, (String) null);
    }

    public UnknownTask(String taskId, Throwable cause) {
        this(taskId, null, cause);
    }

    public UnknownTask(String taskId, String message) {
        super(message == null ? "task \"" + taskId + "\" is unknown" : message);
        this.taskId = taskId;
    }

    public UnknownTask(String taskId, String message, Throwable cause) {
        super(message == null ? "task \"" + taskId + "\" is unknown" : message, cause);
        this.taskId = taskId;
    }
}
