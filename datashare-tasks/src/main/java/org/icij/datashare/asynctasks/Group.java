package org.icij.datashare.asynctasks;

import java.util.Objects;
import org.icij.datashare.Entity;

public record Group(String id) implements Entity {
    @Override
    public String getId() {
        return id();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        return  Objects.equals(id, ((Group) o).getId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
