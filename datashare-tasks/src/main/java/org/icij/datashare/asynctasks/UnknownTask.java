package org.icij.datashare.asynctasks;

public class UnknownTask extends RuntimeException {
    final String taskId;

    public UnknownTask(String taskId) {
        this.taskId = taskId;
    }
}
