package org.icij.datashare.asynctasks;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.io.Serializable;

/**
 * The value must be decodable by the typed JsonObjectMapper: its class registered as a subtype
 * and every nested polymorphic type inside the type allowlist. Otherwise the Redis task manager
 * cannot decode the result and marks the task as an error.
 */
public record TaskResult<V extends Serializable>(@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type") V value)
        implements Serializable {
    public TaskResult(V value) {
        this.value = value;
    }
}
