package org.icij.datashare.asynctasks;

public class UnknownTask extends RuntimeException {
    final String taskId;

    public UnknownTask(String taskId) {
        super("unknown task \"%s\"".formatted(taskId));
        this.taskId = taskId;
    }
}
