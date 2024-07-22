package org.icij.datashare.asynctasks.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.Serializable;
import java.net.URI;
import java.util.Objects;

import static java.lang.String.format;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
public class UriResult implements Serializable {
    public final URI uri;
    public final long size;

    @JsonCreator
    public UriResult(@JsonProperty("uri") URI uri, @JsonProperty("size") long size) {
        this.uri = uri;
        this.size = size;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UriResult uriResult = (UriResult) o;
        return Objects.equals(uri, uriResult.uri)
                && Objects.equals(size, uriResult.size);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri, size);
    }

    @Override
    public String toString() {
        return format("%s (%d bytes)", uri, size);
    }
}
