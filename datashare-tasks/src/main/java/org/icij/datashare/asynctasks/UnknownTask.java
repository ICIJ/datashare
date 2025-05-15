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
        super("unknown task \"%s\": %s".formatted(taskId, message));
        this.taskId = taskId;
    }

    public UnknownTask(String taskId, String message, Throwable cause) {
        super("unknown task \"%s\"".formatted(taskId), cause);
        this.taskId = taskId;
    }
}
