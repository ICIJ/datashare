package org.icij.datashare.asynctasks;

public class UnknownTask extends Exception {
    final String taskId;

    public UnknownTask(String taskId) {
        this.taskId = taskId;
    }
}
