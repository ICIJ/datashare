package org.icij.datashare.asynctasks;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.Serializable;


public record TaskResult<V extends Serializable> (
        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
        V value) implements Serializable {
    public TaskResult(V value) {
        this.value = value;
    }
}
