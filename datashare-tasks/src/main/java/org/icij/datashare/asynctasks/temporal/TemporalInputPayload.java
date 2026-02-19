package org.icij.datashare.asynctasks.temporal;


import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serializable;
import java.util.Map;

public record TemporalInputPayload(@JsonValue Map<String, Object> args) implements Serializable {
    @JsonCreator
    public static TemporalInputPayload fromMap(Map<String, Object> args) {
        return new TemporalInputPayload(args);
    }
}