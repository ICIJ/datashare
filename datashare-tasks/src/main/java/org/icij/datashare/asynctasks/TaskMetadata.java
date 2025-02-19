package org.icij.datashare.asynctasks;

import java.io.Serializable;

public record TaskMetadata<V extends Serializable>(Task<V> task, Group group) {
    public String taskId() {
        return task.id;
    }

    TaskMetadata<?> withTask(Task<?> task) {
        return new TaskMetadata<>(task, this.group);
    }
}
