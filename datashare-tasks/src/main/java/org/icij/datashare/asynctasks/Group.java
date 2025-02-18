package org.icij.datashare.asynctasks;

import org.icij.datashare.Entity;

public record Group(TaskGroupType id) implements Entity {
    @Override
    public String getId() {
        return id().name();
    }
}
