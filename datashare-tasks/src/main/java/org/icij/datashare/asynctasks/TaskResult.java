package org.icij.datashare.asynctasks;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.io.Serializable;
import java.util.Objects;


// We can't use a record class here because they are final, and we want to allow inheritance
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
public class TaskResult<V extends Serializable> implements Serializable {
    protected V value;

    public TaskResult(V value) {
        this.value = value;
    }

    public V getValue() {
        return value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TaskResult<?>)) {
            return false;
        }

        return ((TaskResult<?>) o).value.equals(value);
    }
}
