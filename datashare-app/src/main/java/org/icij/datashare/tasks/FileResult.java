package org.icij.datashare.tasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.File;
import java.io.Serializable;

import static java.lang.String.format;

public class FileResult implements Serializable {
    public final File file;
    public final long size;

    @JsonCreator
    public FileResult(@JsonProperty("file") File file, @JsonProperty("size") long size) {
        this.file = file;
        this.size = size;
    }

    @Override
    public String toString() {
        return format("%s (%d bytes)", file, size);
    }
}
