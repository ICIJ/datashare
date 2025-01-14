package org.icij.datashare.asynctasks;

public class TaskAlreadyExists extends Exception {
    final String taskId;

    public TaskAlreadyExists(String taskId) {
        this.taskId = taskId;
    }
}
