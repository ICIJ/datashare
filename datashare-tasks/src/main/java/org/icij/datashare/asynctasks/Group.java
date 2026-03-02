package org.icij.datashare.asynctasks;

import org.icij.datashare.Entity;

public record Group(String id) implements Entity {
    @Override
    public String getId() {
        return id();
    }

    public Group(TaskGroupType taskGroupId) {
        this(taskGroupId.name());
    }
}
