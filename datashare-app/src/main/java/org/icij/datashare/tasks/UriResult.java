package org.icij.datashare.tasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.File;
import java.io.Serializable;
import java.net.URI;
import java.util.Objects;

import static java.lang.String.format;

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
