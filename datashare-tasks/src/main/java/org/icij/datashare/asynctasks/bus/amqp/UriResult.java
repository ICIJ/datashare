package org.icij.datashare.asynctasks.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.icij.datashare.asynctasks.DownloadableResult;

import java.io.Serializable;
import java.net.URI;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
public record UriResult(URI uri, long size) implements Serializable, DownloadableResult {
    @JsonCreator
    public UriResult(@JsonProperty("uri") URI uri, @JsonProperty("size") long size) {
        this.uri = uri;
        this.size = size;
    }

    @Override
    public URI getUri() {
        return uri;
    }
}
