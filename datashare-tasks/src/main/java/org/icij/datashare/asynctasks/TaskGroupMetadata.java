package org.icij.datashare.asynctasks;

import java.io.Serializable;

public record TaskGroupMetadata<V extends Serializable>(Task<V> task, Group group) {
    public String taskId() {
        return task.id;
    }

    TaskGroupMetadata<?> withTask(Task<?> task) {
        return new TaskGroupMetadata<>(task, this.group);
    }
}
