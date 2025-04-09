package org.icij.datashare.tasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.Serializable;
import java.net.URI;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
public record UriResult(URI uri, long size) implements Serializable {
    @JsonCreator
    public UriResult(@JsonProperty("uri") URI uri, @JsonProperty("size") long size) {
        this.uri = uri;
        this.size = size;
    }
}
