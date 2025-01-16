package org.icij.datashare.asynctasks;

public record TaskMetadata<V>(Task<V> task, Group group) {
    String taskId() {
        return task.id;
    }

    TaskMetadata<V> withTask(Task<V> task) {
        return new TaskMetadata<>(task, this.group);
    }
}
