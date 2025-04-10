package org.icij.datashare.asynctasks;

public record TaskMetadata(Task task, Group group) {
    public String taskId() {
        return task.id;
    }

    TaskMetadata withTask(Task task) {
        return new TaskMetadata(task, this.group);
    }
}
