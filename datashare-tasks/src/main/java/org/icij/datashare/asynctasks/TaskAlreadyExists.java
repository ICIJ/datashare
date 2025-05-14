package org.icij.datashare.asynctasks;

public class TaskAlreadyExists extends RuntimeException {
    final String taskId;

    public TaskAlreadyExists(String taskId) {
        this.taskId = taskId;
    }

    public TaskAlreadyExists(String taskId, Throwable cause) {
        super(cause);
        this.taskId = taskId;
    }
}
