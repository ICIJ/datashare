package org.icij.datashare.asynctasks;

import java.util.Objects;

public record TaskMetadata<V>(Task<V> task, Group group) {
    public String taskId() {
        return task.id;
    }

    TaskMetadata<V> withTask(Task<V> task) {
        return new TaskMetadata<>(task, this.group);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        TaskMetadata<?> otherMeta = (TaskMetadata<?>) o;
        return  Objects.equals(task, otherMeta.task) &&
                Objects.equals(group, otherMeta.group);
    }

    @Override
    public int hashCode() {
        return Objects.hash(task, group);
    }
}
