package org.icij.datashare.asynctasks;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.icij.datashare.asynctasks.bus.amqp.UriResult;

import java.io.Serializable;


public record TaskResult<V extends Serializable> (
        @JsonSubTypes({
                @JsonSubTypes.Type(value = UriResult.class),
                @JsonSubTypes.Type(value = Long.class)
        })
        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
        V value) implements Serializable {
    public TaskResult(V value) {
        this.value = value;
    }
}
