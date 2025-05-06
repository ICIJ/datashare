package org.icij.datashare.tasks;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.io.Serializable;

@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@type")
public record DatashareTaskResult<V extends Serializable>(
    @JsonSubTypes({
        @JsonSubTypes.Type(value = UriResult.class),
        @JsonSubTypes.Type(value = Long.class),
        @JsonSubTypes.Type(value = SerializableList.class),
    })
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
    V value) implements Serializable {
    public DatashareTaskResult(V value) {
        this.value = value;
    }
}
